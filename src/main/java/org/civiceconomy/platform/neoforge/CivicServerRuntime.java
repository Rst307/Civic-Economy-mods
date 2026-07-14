package org.civiceconomy.platform.neoforge;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPayments;
import org.civiceconomy.nation.OnlineSessionAccumulator;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.nation.NationApplicationExpiryProcessor;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.nation.CitizenshipReconciler;
import org.civiceconomy.nation.CitizenshipReconciliationResult;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.nation.RegisteredNation;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.territory.CommittedTerritoryPrepaymentVerifier;
import org.civiceconomy.territory.TerritoryClaimPermitCompensationCoordinator;
import org.civiceconomy.territory.ConsumeTerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPermitEventBridge;
import org.civiceconomy.territory.TerritoryClaimPermitMirror;
import org.civiceconomy.territory.TerritoryClaimPermitRegistry;
import org.civiceconomy.territory.TerritoryFiscalServiceProvisioner;
import org.slf4j.Logger;

public final class CivicServerRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECKPOINT_INTERVAL_TICKS = 20 * 60;
    private static final int NATION_APPLICATION_EXPIRY_INTERVAL_TICKS = 20 * 60;
    private static final int CITIZENSHIP_RECONCILIATION_INTERVAL_TICKS = 20 * 60;
    private static final int TERRITORY_PERMIT_COMPENSATION_INTERVAL_TICKS = 20 * 60;
    private static final Duration NATION_APPLICATION_EVIDENCE_WINDOW = Duration.ofDays(60);
    private static final Duration CITIZENSHIP_CORRECTION_GRACE = Duration.ofDays(2);
    private static final Duration CITIZENSHIP_TRANSFER_COOLDOWN = Duration.ofDays(7);
    private static final NationTeamDirectory NO_TEAM_LOOKUPS = new NationTeamDirectory() {
        @Override
        public Optional<NationTeam> find(UUID teamId) {
            return Optional.empty();
        }

        @Override
        public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
            return Optional.empty();
        }
    };
    private static volatile CivicServerRuntime current;

    private final Clock clock;
    private final TerritoryClaimPermitMirror territoryClaimPermitMirror =
            new TerritoryClaimPermitMirror();
    private final Map<UUID, org.civiceconomy.nation.NationId> nationByFtbTeam =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean territoryClaimAuthorizationReady = new AtomicBoolean();
    private RuntimeState state;

    public CivicServerRuntime() {
        this(Clock.systemUTC());
    }

    CivicServerRuntime(Clock clock) {
        this.clock = clock;
        current = this;
        new FtbTerritoryClaimPermitEvents(
                        this,
                        new TerritoryClaimPermitEventBridge(
                                territoryClaimPermitMirror,
                                this::queueTerritoryClaimPermitConsumption,
                                clock))
                .register();
    }

    static CivicServerRuntime current() {
        CivicServerRuntime runtime = current;
        if (runtime == null) {
            throw new IllegalStateException("Civic server runtime has not been constructed");
        }
        return runtime;
    }

    public void onServerStarted(ServerStartedEvent event) {
        if (state != null) {
            throw new IllegalStateException("Civic server runtime is already active");
        }
        MinecraftServer server = event.getServer();
        Path databaseDirectory = server.getWorldPath(LevelResource.ROOT).resolve("civiceconomy");
        try {
            Files.createDirectories(databaseDirectory);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to create Civic database directory " + databaseDirectory, failure);
        }
        NeoForgeModCatalog mods = new NeoForgeModCatalog();
        DatabaseIdentity identity = new DatabaseIdentity(
                CivicWorldIdentityData.getOrCreate(server),
                requireVersion(mods, CivicEconomy.MOD_ID),
                requireVersion(mods, "lightmanscurrency"),
                requireVersion(mods, "ftbteams"),
                requireVersion(mods, "ftbchunks"));
        CivicDatabase database = CivicDatabase.open(databaseDirectory.resolve("civic.sqlite3"), identity);
        AsyncOnlineTimeWriter writer = new AsyncOnlineTimeWriter(database);
        OnlineSessionAccumulator sessions =
                new OnlineSessionAccumulator(new ServiceIdentity("civiceconomy-server"));
        long now = clock.millis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sessions.login(player.getUUID(), now);
        }
        state = new RuntimeState(server, sessions, writer);
        scheduleTerritoryPermitMirrorRefresh(state);
        scheduleNationApplicationExpiry(state);
        scheduleCitizenshipReconciliation(state);
        scheduleTerritoryPermitCompensation(state);
        LOGGER.info("Civic server runtime opened world-bound SQLite and enabled buffered online-time observation");
        if (CivicDebugWorldData.get(server).enabled()) {
            LOGGER.warn(
                    "DEBUG WORLD is active for {}; Civic debug data must not be represented as formal economy data",
                    server.getWorldData().getLevelName());
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            observeLogin(player);
        }
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            observeLogout(player);
        }
    }

    void observeLogin(ServerPlayer player) {
        RuntimeState current = state;
        if (current != null) {
            current.sessions.login(player.getUUID(), clock.millis());
        }
    }

    void observeLogout(ServerPlayer player) {
        RuntimeState current = state;
        if (current != null) {
            current.writer.submit(current.sessions.logout(player.getUUID(), clock.millis()));
        }
    }

    public void onServerTick(ServerTickEvent.Post event) {
        RuntimeState current = state;
        if (current == null || current.server != event.getServer()) {
            return;
        }
        current.ticksSinceCheckpoint++;
        if (current.ticksSinceCheckpoint >= CHECKPOINT_INTERVAL_TICKS) {
            current.ticksSinceCheckpoint = 0;
            current.writer.submit(current.sessions.checkpoint(clock.millis()));
        }
        current.ticksSinceNationApplicationExpiry++;
        if (current.ticksSinceNationApplicationExpiry
                >= NATION_APPLICATION_EXPIRY_INTERVAL_TICKS) {
            current.ticksSinceNationApplicationExpiry = 0;
            scheduleNationApplicationExpiry(current);
        }
        current.ticksSinceCitizenshipReconciliation++;
        if (current.ticksSinceCitizenshipReconciliation
                >= CITIZENSHIP_RECONCILIATION_INTERVAL_TICKS) {
            current.ticksSinceCitizenshipReconciliation = 0;
            scheduleCitizenshipReconciliation(current);
        }
        current.ticksSinceTerritoryPermitCompensation++;
        if (current.ticksSinceTerritoryPermitCompensation
                >= TERRITORY_PERMIT_COMPENSATION_INTERVAL_TICKS) {
            current.ticksSinceTerritoryPermitCompensation = 0;
            scheduleTerritoryPermitCompensation(current);
        }
        Throwable failure = current.writer.failure();
        if (failure != null && !current.failureLogged) {
            current.failureLogged = true;
            LOGGER.error("Civic online-time persistence failed closed; new intervals are paused", failure);
        }
    }

    public void onServerStopping(ServerStoppingEvent event) {
        RuntimeState current = state;
        if (current != null && current.server == event.getServer()) {
            List<RecordOnlineTime> finalIntervals = current.sessions.checkpoint(clock.millis());
            current.writer.submit(finalIntervals);
        }
    }

    public void onServerStopped(ServerStoppedEvent event) {
        RuntimeState current = state;
        if (current == null || current.server != event.getServer()) {
            return;
        }
        try {
            current.writer.close();
        } finally {
            state = null;
            territoryClaimAuthorizationReady.set(false);
            territoryClaimPermitMirror.replaceAll(List.of());
            nationByFtbTeam.clear();
        }
        LOGGER.info("Civic server runtime closed SQLite after draining buffered online-time intervals");
    }

    <T> CompletableFuture<T> submitAdministration(
            Function<FiscalAuthorization, T> operation) {
        return submitDatabase(database -> operation.apply(new FiscalAuthorization(database)));
    }

    <T> CompletableFuture<T> submitDatabase(Function<CivicDatabase, T> operation) {
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        return current.writer.submitDatabase(operation);
    }

    org.civiceconomy.nation.NationId nationForFtbTeam(UUID ftbTeamId) {
        return nationByFtbTeam.get(ftbTeamId);
    }

    boolean territoryClaimAuthorizationReady() {
        return territoryClaimAuthorizationReady.get();
    }

    private void scheduleTerritoryPermitMirrorRefresh(RuntimeState current) {
        Clock refreshClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> {
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database,
                                    TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            refreshClock);
                    Map<UUID, org.civiceconomy.nation.NationId> bindings =
                            new HashMap<>();
                    for (RegisteredNation nation
                            : new NationRegistry(database, NO_TEAM_LOOKUPS).registeredNations()) {
                        bindings.put(nation.ftbTeamId(), nation.nationId());
                    }
                    return new TerritoryPermitMirrorSnapshot(
                            permits.readyPermits(), Map.copyOf(bindings));
                })
                .whenComplete((snapshot, failure) -> {
                    if (failure != null) {
                        LOGGER.error(
                                "Territory Claim Permit mirror refresh failed closed", failure);
                        territoryClaimPermitMirror.replaceAll(List.of());
                        nationByFtbTeam.clear();
                        territoryClaimAuthorizationReady.set(false);
                        return;
                    }
                    territoryClaimPermitMirror.replaceAll(snapshot.permits());
                    nationByFtbTeam.clear();
                    nationByFtbTeam.putAll(snapshot.nationByFtbTeam());
                    territoryClaimAuthorizationReady.set(true);
                });
    }

    void publishTerritoryClaimPermit(
            org.civiceconomy.territory.TerritoryClaimPermit permit) {
        nationByFtbTeam.compute(permit.ftbTeamId(), (ignored, existing) -> {
            if (existing != null && !existing.equals(permit.nationId())) {
                throw new IllegalStateException(
                        "FTB Team is already mirrored for a different Nation");
            }
            return permit.nationId();
        });
        territoryClaimPermitMirror.publish(permit);
    }

    void removeTerritoryClaimPermit(
            org.civiceconomy.territory.TerritoryClaimPermit permit) {
        territoryClaimPermitMirror.remove(permit);
    }

    private void queueTerritoryClaimPermitConsumption(
            org.civiceconomy.territory.TerritoryClaimPermitConsumptionIntent intent) {
        RuntimeState current = state;
        if (current == null) {
            LOGGER.error(
                    "Territory Claim Permit consumption was acquired without an active runtime: {}",
                    intent.permitId());
            return;
        }
        current.writer.submitDatabase(database -> {
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database,
                                    TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            Clock.fixed(intent.claimedAt(), ZoneOffset.UTC));
                    return permits.consume(new ConsumeTerritoryClaimPermit(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                            "ftb-after-claim:" + intent.permitId(),
                            intent.permitId(),
                            intent.target().nationId(),
                            intent.target().ftbTeamId(),
                            intent.target().actorPlayerId(),
                            intent.target().dimensionId(),
                            intent.target().chunkX(),
                            intent.target().chunkZ()));
                })
                .whenComplete((consumed, failure) -> {
                    if (failure != null) {
                        LOGGER.error(
                                "Territory Claim Permit durable consumption failed closed after FTB claim",
                                failure);
                    }
                });
    }

    private void scheduleNationApplicationExpiry(RuntimeState current) {
        if (!current.nationApplicationExpiryQueued.compareAndSet(false, true)) {
            return;
        }
        Clock scanClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> new NationApplicationExpiryProcessor(
                        database, scanClock, NATION_APPLICATION_EVIDENCE_WINDOW)
                .expireDue())
                .whenComplete((expired, failure) -> {
                    current.nationApplicationExpiryQueued.set(false);
                    if (failure != null) {
                        LOGGER.error("Automatic Nation Application expiry failed closed", failure);
                    } else if (!expired.isEmpty()) {
                        LOGGER.info("Automatically expired {} Nation Application(s)", expired.size());
                    }
                });
    }

    private void scheduleCitizenshipReconciliation(RuntimeState current) {
        if (!current.citizenshipReconciliationQueued.compareAndSet(false, true)) {
            return;
        }
        Clock scanClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database ->
                        new NationRegistry(database, NO_TEAM_LOOKUPS).registeredNations())
                .whenComplete((nations, listFailure) -> {
                    if (listFailure != null) {
                        finishCitizenshipReconciliation(current, null, listFailure);
                        return;
                    }
                    current.server.execute(() -> {
                        try {
                            snapshotAndReconcile(current, nations, scanClock);
                        } catch (RuntimeException failure) {
                            finishCitizenshipReconciliation(current, null, failure);
                        }
                    });
                });
    }

    private void snapshotAndReconcile(
            RuntimeState current, List<RegisteredNation> nations, Clock scanClock) {
        if (state != current) {
            current.citizenshipReconciliationQueued.set(false);
            return;
        }
        Map<UUID, NationTeam> snapshots = new HashMap<>();
        FtbNationTeamDirectory liveTeams = FtbNationTeamDirectory.live();
        int unavailable = 0;
        for (RegisteredNation nation : nations) {
            Optional<NationTeam> team = liveTeams.find(nation.ftbTeamId());
            if (team.isPresent()) {
                snapshots.put(nation.ftbTeamId(), team.orElseThrow());
            } else {
                unavailable++;
            }
        }
        if (unavailable > 0) {
            LOGGER.warn(
                    "Skipped Citizenship reconciliation for {} Nation(s) with unavailable FTB Team facts",
                    unavailable);
        }
        NationTeamDirectory snapshotDirectory = snapshotDirectory(snapshots);
        current.writer.submitDatabase(database -> {
                    int started = 0;
                    int restored = 0;
                    int ended = 0;
                    CitizenshipReconciler reconciler = new CitizenshipReconciler(
                            database,
                            snapshotDirectory,
                            CITIZENSHIP_CORRECTION_GRACE,
                            CITIZENSHIP_TRANSFER_COOLDOWN,
                            scanClock);
                    for (RegisteredNation nation : nations) {
                        if (!snapshots.containsKey(nation.ftbTeamId())) {
                            continue;
                        }
                        CitizenshipReconciliationResult result =
                                reconciler.reconcile(nation.nationId());
                        started += result.startedGraceCount();
                        restored += result.restoredCount();
                        ended += result.endedCitizenshipCount();
                    }
                    return new CitizenshipReconciliationResult(started, restored, ended);
                })
                .whenComplete((result, failure) ->
                        finishCitizenshipReconciliation(current, result, failure));
    }

    private static NationTeamDirectory snapshotDirectory(Map<UUID, NationTeam> snapshots) {
        Map<UUID, NationTeam> immutable = Map.copyOf(snapshots);
        return new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                return Optional.ofNullable(immutable.get(teamId));
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return immutable.values().stream()
                        .filter(team -> team.citizens().contains(playerId))
                        .findFirst();
            }
        };
    }

    private static void finishCitizenshipReconciliation(
            RuntimeState current,
            CitizenshipReconciliationResult result,
            Throwable failure) {
        current.citizenshipReconciliationQueued.set(false);
        if (failure != null) {
            LOGGER.error("Automatic Citizenship reconciliation failed closed", failure);
        } else if (result != null
                && (result.startedGraceCount() > 0
                        || result.restoredCount() > 0
                        || result.endedCitizenshipCount() > 0)) {
            LOGGER.info(
                    "Citizenship reconciliation started {} grace(s), restored {}, ended {}",
                    result.startedGraceCount(),
                    result.restoredCount(),
                    result.endedCitizenshipCount());
        }
    }

    private void scheduleTerritoryPermitCompensation(RuntimeState current) {
        if (!current.territoryPermitCompensationQueued.compareAndSet(false, true)) {
            return;
        }
        Clock scanClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> {
                    boolean hasWork = !database.incompleteTerritoryClaimPermitCompensations()
                                    .isEmpty()
                            || !database.dueTerritoryClaimPermits(scanClock.millis()).isEmpty();
                    if (!hasWork) {
                        return new TerritoryPermitCompensationResult(0, 0);
                    }
                    var session = new FiscalAuthorization(database)
                            .openSession(TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database,
                                    TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            scanClock);
                    TerritoryClaimPermitCompensationCoordinator coordinator =
                            new TerritoryClaimPermitCompensationCoordinator(
                                    database,
                                    PaymentCoordinator.authorized(
                                            database,
                                            LightmansCurrencyPayments.live(current.server.overworld()),
                                            session),
                                    permits,
                                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    scanClock);
                    int recovered = coordinator.recoverIncomplete().size();
                    int expired = coordinator.expireDue().size();
                    return new TerritoryPermitCompensationResult(recovered, expired);
                })
                .whenComplete((result, failure) -> {
                    current.territoryPermitCompensationQueued.set(false);
                    if (failure != null) {
                        LOGGER.error(
                                "Automatic Territory Claim Permit compensation failed closed",
                                failure);
                    } else if (result.recovered() > 0 || result.expired() > 0) {
                        LOGGER.info(
                                "Recovered {} and expired {} Territory Claim Permit compensation(s)",
                                result.recovered(),
                                result.expired());
                        scheduleTerritoryPermitMirrorRefresh(current);
                    }
                });
    }

    private static String requireVersion(NeoForgeModCatalog mods, String modId) {
        return mods.version(modId)
                .orElseThrow(() -> new IllegalStateException("Loaded mod version is unavailable: " + modId));
    }

    private static final class RuntimeState {
        private final MinecraftServer server;
        private final OnlineSessionAccumulator sessions;
        private final AsyncOnlineTimeWriter writer;
        private final AtomicBoolean nationApplicationExpiryQueued = new AtomicBoolean();
        private final AtomicBoolean citizenshipReconciliationQueued = new AtomicBoolean();
        private final AtomicBoolean territoryPermitCompensationQueued = new AtomicBoolean();
        private int ticksSinceCheckpoint;
        private int ticksSinceNationApplicationExpiry;
        private int ticksSinceCitizenshipReconciliation;
        private int ticksSinceTerritoryPermitCompensation;
        private boolean failureLogged;

        private RuntimeState(
                MinecraftServer server, OnlineSessionAccumulator sessions, AsyncOnlineTimeWriter writer) {
            this.server = server;
            this.sessions = sessions;
            this.writer = writer;
        }
    }

    private record TerritoryPermitCompensationResult(int recovered, int expired) {}

    private record TerritoryPermitMirrorSnapshot(
            List<org.civiceconomy.territory.TerritoryClaimPermit> permits,
            Map<UUID, org.civiceconomy.nation.NationId> nationByFtbTeam) {}
}
