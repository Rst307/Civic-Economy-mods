package org.civiceconomy.platform.neoforge;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyAccountBalances;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyTerritoryClearingAccountProvisioner;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPublicMaintenanceFundProvisioner;
import org.civiceconomy.integration.lightmanscurrency.PermanentDestructionCoordinator;
import org.civiceconomy.integration.lightmanscurrency.TerritoryMaintenancePaymentCoordinator;
import org.civiceconomy.nation.OnlineSessionAccumulator;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.nation.NationApplicationExpiryProcessor;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.integration.ftb.FtbChunksAdapter;
import org.civiceconomy.nation.Capital;
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
import org.civiceconomy.territory.FreeClaimAuthorization;
import org.civiceconomy.territory.FreeClaimAuthorizationMirror;
import org.civiceconomy.territory.TerritoryClaimTarget;
import org.civiceconomy.territory.TerritoryClaimPermitRegistry;
import org.civiceconomy.territory.TerritoryFiscalServiceProvisioner;
import org.civiceconomy.territory.PrepareTerritoryClaimPrepayment;
import org.civiceconomy.territory.CancelTerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPrepaymentCoordinator;
import org.civiceconomy.territory.TerritoryExpansionPricingPolicyRegistry;
import org.civiceconomy.territory.TerritoryExpansionPricingPolicyVersion;
import org.civiceconomy.territory.TerritoryFreeAllocationPolicyRegistry;
import org.civiceconomy.territory.TerritoryFreeAllocationPolicyVersion;
import org.civiceconomy.territory.AssessTerritoryMaintenanceCycle;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.civiceconomy.territory.TerritoryFreeAllocation;
import org.civiceconomy.territory.TerritoryMaintenanceAssessmentBatch;
import org.civiceconomy.territory.TerritoryMaintenanceAssessmentPlanner;
import org.civiceconomy.territory.TerritoryMaintenanceAssessmentProcessor;
import org.civiceconomy.territory.TerritoryMaintenanceCycleSchedule;
import org.civiceconomy.territory.TerritoryMaintenanceCycleWindow;
import org.civiceconomy.territory.TerritoryMaintenanceObservedClaim;
import org.civiceconomy.territory.TerritoryMaintenancePolicyRegistry;
import org.civiceconomy.territory.TerritoryMaintenancePolicyVersion;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.civiceconomy.territory.TerritoryMaintenanceRestorationHistory;
import org.civiceconomy.territory.SettleAvailableTerritoryMaintenance;
import org.civiceconomy.territory.TerritoryFiscalValidity;
import org.civiceconomy.territory.TerritoryMaintenanceSettlementOutcome;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalLedger;
import org.slf4j.Logger;

public final class CivicServerRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECKPOINT_INTERVAL_TICKS = 20 * 60;
    private static final int NATION_APPLICATION_EXPIRY_INTERVAL_TICKS = 20 * 60;
    private static final int CITIZENSHIP_RECONCILIATION_INTERVAL_TICKS = 20 * 60;
    private static final int TERRITORY_PERMIT_COMPENSATION_INTERVAL_TICKS = 20 * 60;
    private static final int PERMANENT_DESTRUCTION_RECOVERY_INTERVAL_TICKS = 20 * 60;
    private static final int TERRITORY_MAINTENANCE_ASSESSMENT_INTERVAL_TICKS = 20 * 60;
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
    private final FreeClaimAuthorizationMirror freeClaimAuthorizationMirror =
            new FreeClaimAuthorizationMirror();
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
                                freeClaimAuthorizationMirror,
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
        LightmansCurrencyPublicMaintenanceFundProvisioner.forLevel(server.overworld())
                .ensureExists();
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
        schedulePermanentDestructionRecovery(state);
        scheduleTerritoryMaintenanceAssessment(state);
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
        current.ticksSincePermanentDestructionRecovery++;
        if (current.ticksSincePermanentDestructionRecovery
                >= PERMANENT_DESTRUCTION_RECOVERY_INTERVAL_TICKS) {
            current.ticksSincePermanentDestructionRecovery = 0;
            schedulePermanentDestructionRecovery(current);
        }
        current.ticksSinceTerritoryMaintenanceAssessment++;
        if (current.ticksSinceTerritoryMaintenanceAssessment
                >= TERRITORY_MAINTENANCE_ASSESSMENT_INTERVAL_TICKS) {
            current.ticksSinceTerritoryMaintenanceAssessment = 0;
            scheduleTerritoryMaintenanceAssessment(current);
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
            freeClaimAuthorizationMirror.clear();
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

    CompletableFuture<PreparedTerritoryClaim> prepareTerritoryClaim(
            NationTeam team,
            UUID actorPlayerId,
            String requestId,
            String dimensionId,
            int chunkX,
            int chunkZ,
            int currentClaimedChunks) {
        Instant commandTime = clock.instant();
        Clock commandClock = Clock.fixed(commandTime, ZoneOffset.UTC);
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        var freeReplay = freeClaimAuthorizationMirror.requireActiveReplay(
                requestId, commandTime);
        if (freeReplay.isPresent()) {
            FreeClaimAuthorization authorization = freeReplay.orElseThrow();
            TerritoryClaimTarget target = authorization.target();
            if (!target.nationId().equals(nationForFtbTeam(team.teamId()))
                    || !target.ftbTeamId().equals(team.teamId())
                    || !target.actorPlayerId().equals(actorPlayerId)
                    || !target.dimensionId().equals(dimensionId)
                    || target.chunkX() != chunkX
                    || target.chunkZ() != chunkZ) {
                return CompletableFuture.failedFuture(
                        new org.civiceconomy.fiscal.IdempotencyConflictException(
                                TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                requestId));
            }
            return CompletableFuture.completedFuture(
                    new PreparedTerritoryClaim(null, authorization));
        }
        return current.writer.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var citizenships = new org.civiceconomy.nation.CitizenshipRegistry(
                            database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock);
                    var provider = new org.civiceconomy.nation.FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry(
                                    database, commandClock),
                            teams);
                    var actorNation = provider.findForCitizen(actorPlayerId)
                            .orElseThrow(() -> new SecurityException(
                                    "Free Claim actor has no effective formal Citizenship"));
                    if (!actorNation.nationId().equals(nation.nationId())) {
                        throw new SecurityException(
                                "Free Claim actor does not belong to the exact Nation");
                    }
                    var population = new org.civiceconomy.nation.NationPopulationCalculator(
                                    citizenships,
                                    new org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry(
                                            database, commandClock),
                                    new org.civiceconomy.nation.OnlineTimeLedger(database),
                                    NATION_APPLICATION_EVIDENCE_WINDOW,
                                    Duration.ofHours(8))
                            .calculate(nation.nationId(), commandTime);
                    var allocation = new TerritoryFreeAllocationPolicyRegistry(
                                    database,
                                    commandClock,
                                    TerritoryFreeAllocationPolicyVersion.defaultPolicy(0, 0))
                            .current(commandTime)
                            .policy()
                            .calculate(population);
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database, TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            commandClock);
                    var replay = permits.findByRequest(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                            requestId + ":permit");
                    if (replay.isPresent()) {
                        TerritoryClaimPermit permit = replay.orElseThrow();
                        if (!permit.nationId().equals(nation.nationId())
                                || !permit.ftbTeamId().equals(team.teamId())
                                || !permit.actorPlayerId().equals(actorPlayerId)
                                || !permit.dimensionId().equals(dimensionId)
                                || permit.chunkX() != chunkX
                                || permit.chunkZ() != chunkZ) {
                            throw new org.civiceconomy.fiscal.IdempotencyConflictException(
                                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    requestId);
                        }
                        return new PreparedTerritoryClaim(permit, null);
                    }
                    int pendingClaims = Math.toIntExact(permits.readyPermits().stream()
                            .filter(permit -> permit.nationId().equals(nation.nationId()))
                            .count());
                    pendingClaims = Math.addExact(
                            pendingClaims,
                            freeClaimAuthorizationMirror.pendingCount(
                                    nation.nationId(), commandTime));
                    int quotedClaimedChunks = Math.addExact(
                            currentClaimedChunks, pendingClaims);
                    var quote = new TerritoryExpansionPricingPolicyRegistry(
                                    database,
                                    commandClock,
                                    TerritoryExpansionPricingPolicyVersion.defaultPolicy(0, 0))
                            .current(commandTime)
                            .policy()
                            .quote(allocation, quotedClaimedChunks, 1, commandTime);
                    if (quote.prepayment().minorUnits() <= 0L) {
                        TerritoryClaimTarget target = new TerritoryClaimTarget(
                                nation.nationId(),
                                team.teamId(),
                                actorPlayerId,
                                dimensionId,
                                chunkX,
                                chunkZ);
                        FreeClaimAuthorization freeClaim = new FreeClaimAuthorization(
                                UUID.randomUUID(),
                                requestId,
                                target,
                                commandTime.plus(Duration.ofMinutes(2)));
                        return new PreparedTerritoryClaim(null, freeClaim);
                    }
                    var authorization = new FiscalAuthorization(database);
                    var treasury = new org.civiceconomy.fiscal.AccountId(
                            "nation:" + nation.nationId().value() + ":treasury");
                    new TerritoryFiscalServiceProvisioner(authorization)
                            .ensureAuthorized(treasury);
                    var session = authorization.openSession(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
                    TerritoryClaimPermit permit = new TerritoryClaimPrepaymentCoordinator(
                                    nations,
                                    new org.civiceconomy.nation.NationFiscalAuthorityRegistry(
                                            database, provider, commandClock),
                                    LightmansCurrencyTerritoryClearingAccountProvisioner.forLevel(
                                            current.server.overworld()),
                                    FiscalLedger.authorized(
                                            database,
                                            LightmansCurrencyAccountBalances.live(
                                                    current.server.overworld()),
                                            session),
                                    PaymentCoordinator.authorized(
                                            database,
                                            LightmansCurrencyPayments.live(
                                                    current.server.overworld()),
                                            session),
                                    permits,
                                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID,
                                    commandClock)
                            .prepare(new PrepareTerritoryClaimPrepayment(
                                    requestId,
                                    nation.nationId(),
                                    team.teamId(),
                                    actorPlayerId,
                                    dimensionId,
                                    chunkX,
                                    chunkZ,
                                    allocation.totalFreeChunks(),
                                    quote,
                                    commandTime.plus(Duration.ofMinutes(2))));
                    return new PreparedTerritoryClaim(permit, null);
                })
                .thenApply(prepared -> {
                    TerritoryClaimPermit permit = prepared.permit();
                    if (permit != null && permit.state()
                            == org.civiceconomy.territory.TerritoryClaimPermitState.READY) {
                        publishTerritoryClaimPermit(permit);
                    }
                    if (prepared.freeClaim() != null) {
                        nationByFtbTeam.put(
                                prepared.freeClaim().target().ftbTeamId(),
                                prepared.freeClaim().target().nationId());
                        freeClaimAuthorizationMirror.publish(prepared.freeClaim());
                    }
                    return prepared;
                });
    }

    CompletableFuture<TerritoryClaimPermit> cancelTerritoryClaim(
            UUID actorPlayerId, UUID permitId, String requestId, String reason) {
        Instant commandTime = clock.instant();
        Clock commandClock = Clock.fixed(commandTime, ZoneOffset.UTC);
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        return current.writer.submitDatabase(database -> {
                    var authorization = new FiscalAuthorization(database);
                    var session = authorization.openSession(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database, TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            commandClock);
                    return new TerritoryClaimPermitCompensationCoordinator(
                                    database,
                                    PaymentCoordinator.authorized(
                                            database,
                                            LightmansCurrencyPayments.live(
                                                    current.server.overworld()),
                                            session),
                                    permits,
                                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    commandClock)
                            .cancel(new CancelTerritoryClaimPermit(
                                    requestId, permitId, actorPlayerId, reason));
                })
                .thenApply(permit -> {
                    removeTerritoryClaimPermit(permit);
                    return permit;
                });
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

    private void schedulePermanentDestructionRecovery(RuntimeState current) {
        if (!current.permanentDestructionRecoveryQueued.compareAndSet(false, true)) {
            return;
        }
        Clock recoveryClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> {
                    int operationCount = database.permanentDestructionOperations().size();
                    if (operationCount == 0) {
                        return 0;
                    }
                    var session = new FiscalAuthorization(database)
                            .openSession(TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
                    PermanentDestructionCoordinator.live(
                                    database,
                                    session,
                                    recoveryClock,
                                    current.server.overworld())
                            .recoverAll();
                    return operationCount;
                })
                .whenComplete((recovered, failure) -> {
                    current.permanentDestructionRecoveryQueued.set(false);
                    if (failure != null) {
                        LOGGER.error("Automatic Permanent Destruction recovery failed closed", failure);
                    } else if (recovered != null && recovered > 0) {
                        LOGGER.info("Replayed {} Permanent Destruction operation(s)", recovered);
                    }
                });
    }

    void scheduleTerritoryMaintenanceAssessment() {
        RuntimeState current = state;
        if (current != null) {
            scheduleTerritoryMaintenanceAssessment(current);
        }
    }

    private void scheduleTerritoryMaintenanceAssessment(RuntimeState current) {
        if (!current.territoryMaintenanceAssessmentQueued.compareAndSet(false, true)) {
            return;
        }
        Instant scanTime = clock.instant();
        Clock scanClock = Clock.fixed(scanTime, ZoneOffset.UTC);
        current.writer.submitDatabase(database -> prepareTerritoryMaintenanceAssessment(
                        database, scanTime, scanClock))
                .whenComplete((preparation, failure) -> {
                    if (failure != null) {
                        finishTerritoryMaintenanceAssessment(current, null, failure);
                    } else if (preparation == null) {
                        current.territoryMaintenanceAssessmentQueued.set(false);
                    } else if (preparation.recover()) {
                        recoverTerritoryMaintenanceAssessment(current, preparation, scanClock);
                    } else {
                        current.server.execute(() -> snapshotTerritoryMaintenanceClaims(
                                current, preparation, scanClock));
                    }
                });
    }

    private AutomaticMaintenancePreparation prepareTerritoryMaintenanceAssessment(
            CivicDatabase database, Instant scanTime, Clock scanClock) {
        TerritoryMaintenancePolicyRegistry policies =
                new TerritoryMaintenancePolicyRegistry(database, scanClock);
        TerritoryMaintenancePolicyVersion effectivePolicy;
        TerritoryMaintenanceCycleWindow window;
        var activeCycle = database.territoryMaintenanceCycleAt(scanTime.toEpochMilli());
        if (activeCycle != null) {
            if (!activeCycle.requestId().startsWith("automatic-maintenance:")
                    || !activeCycle.requestId().endsWith(":cycle")) {
                LOGGER.warn(
                        "Automatic Territory Maintenance is waiting for non-automatic Cycle {} to end",
                        activeCycle.cycleId());
                return null;
            }
            String requestId = activeCycle.requestId().substring(
                    0, activeCycle.requestId().length() - ":cycle".length());
            window = new TerritoryMaintenanceCycleWindow(
                    requestId,
                    Instant.ofEpochMilli(activeCycle.startsAtEpochMillis()),
                    Instant.ofEpochMilli(activeCycle.endsAtEpochMillis()));
            var batch = database.territoryMaintenanceAssessmentBatch(activeCycle.cycleId());
            if (batch != null) {
                return new AutomaticMaintenancePreparation(null, window, List.of(), true);
            }
            effectivePolicy = policies.find(policyIdFromAutomaticRequest(requestId))
                    .orElseThrow(() -> new IllegalStateException(
                            "Automatic Territory Maintenance Cycle policy is missing"));
        } else {
            Optional<TerritoryMaintenancePolicyVersion> policy = policies.current(scanTime);
            if (policy.isEmpty()) {
                return null;
            }
            effectivePolicy = policy.orElseThrow();
            window = new TerritoryMaintenanceCycleSchedule()
                    .current(effectivePolicy, scanTime)
                    .orElse(null);
            if (window == null) {
                return null;
            }
        }
        var citizenships = new org.civiceconomy.nation.CitizenshipRegistry(
                database, CITIZENSHIP_TRANSFER_COOLDOWN, scanClock);
        var grace = new org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry(
                database, scanClock);
        var populations = new org.civiceconomy.nation.NationPopulationCalculator(
                citizenships,
                grace,
                new org.civiceconomy.nation.OnlineTimeLedger(database),
                NATION_APPLICATION_EVIDENCE_WINDOW,
                Duration.ofHours(8));
        var allocationPolicy = new TerritoryFreeAllocationPolicyRegistry(
                        database,
                        scanClock,
                        TerritoryFreeAllocationPolicyVersion.defaultPolicy(0, 0))
                .current(scanTime)
                .policy();
        var maintenance = new TerritoryMaintenanceRegistry(database, scanClock);
        List<AutomaticNationMaintenanceContext> nations = new java.util.ArrayList<>();
        int missingCapitals = 0;
        for (RegisteredNation nation
                : new NationRegistry(database, NO_TEAM_LOOKUPS).registeredNations()) {
            var storedCapital = database.nationCapital(nation.nationId().value());
            if (storedCapital == null) {
                missingCapitals++;
                continue;
            }
            Capital capital = new Capital(
                    storedCapital.dimensionId(),
                    storedCapital.chunkX(),
                    storedCapital.chunkZ());
            TerritoryFreeAllocation allocation = allocationPolicy.calculate(
                    populations.calculate(nation.nationId(), scanTime));
            nations.add(new AutomaticNationMaintenanceContext(
                    nation.nationId(),
                    nation.ftbTeamId(),
                    capital,
                    allocation,
                    maintenance.restorationHistory(
                            nation.nationId(), nation.ftbTeamId(), window.startsAt())));
        }
        if (missingCapitals > 0) {
            LOGGER.warn(
                    "Skipped automatic Territory Maintenance for {} Nation(s) without a persistent Capital",
                    missingCapitals);
        }
        return new AutomaticMaintenancePreparation(
                effectivePolicy, window, List.copyOf(nations), false);
    }

    private static UUID policyIdFromAutomaticRequest(String requestId) {
        String prefix = "automatic-maintenance:";
        int finalSeparator = requestId.lastIndexOf(':');
        if (!requestId.startsWith(prefix) || finalSeparator <= prefix.length()) {
            throw new IllegalStateException(
                    "Automatic Territory Maintenance request ID is invalid");
        }
        return UUID.fromString(requestId.substring(prefix.length(), finalSeparator));
    }

    private void snapshotTerritoryMaintenanceClaims(
            RuntimeState current,
            AutomaticMaintenancePreparation preparation,
            Clock scanClock) {
        if (state != current) {
            current.territoryMaintenanceAssessmentQueued.set(false);
            return;
        }
        try {
            FtbNationTeamDirectory teams = FtbNationTeamDirectory.live();
            FtbChunksAdapter chunks = FtbChunksAdapter.live();
            TerritoryMaintenanceAssessmentPlanner planner =
                    new TerritoryMaintenanceAssessmentPlanner();
            List<org.civiceconomy.territory.TerritoryMaintenanceClaimSnapshot> claims =
                    preparation.nations().stream()
                            .flatMap(nation -> {
                                if (teams.find(nation.ftbTeamId()).isEmpty()) {
                                    throw new IllegalStateException(
                                            "FTB Team facts are unavailable for Nation "
                                                    + nation.nationId().value());
                                }
                                List<TerritoryMaintenanceObservedClaim> observed = chunks
                                        .claimsForTeam(nation.ftbTeamId()).stream()
                                        .map(claim -> new TerritoryMaintenanceObservedClaim(
                                                new TerritoryClaimPosition(
                                                        claim.dimension().location().toString(),
                                                        claim.chunkPos().x,
                                                        claim.chunkPos().z),
                                                claim.forceLoadRequested()))
                                        .toList();
                                return planner.plan(
                                                nation.nationId(),
                                                nation.ftbTeamId(),
                                                nation.capital(),
                                                 observed,
                                                 nation.freeAllocation(),
                                                 preparation.policy(),
                                                 preparation.window().startsAt(),
                                                 nation.restorationHistory())
                                         .stream();
                            })
                            .toList();
            persistTerritoryMaintenanceAssessment(
                    current, preparation, claims, scanClock);
        } catch (RuntimeException failure) {
            finishTerritoryMaintenanceAssessment(current, null, failure);
        }
    }

    private void persistTerritoryMaintenanceAssessment(
            RuntimeState current,
            AutomaticMaintenancePreparation preparation,
            List<org.civiceconomy.territory.TerritoryMaintenanceClaimSnapshot> claims,
            Clock scanClock) {
        current.writer.submitDatabase(database -> new TerritoryMaintenanceAssessmentProcessor(
                        new TerritoryMaintenanceRegistry(database, scanClock))
                .assess(new AssessTerritoryMaintenanceCycle(
                        TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                        preparation.window().requestId(),
                        preparation.window().startsAt(),
                        preparation.window().endsAt(),
                        claims,
                        "Automatic Territory Maintenance assessment")))
                .whenComplete((batch, failure) ->
                        finishTerritoryMaintenanceAssessment(current, batch, failure));
    }

    private void recoverTerritoryMaintenanceAssessment(
            RuntimeState current,
            AutomaticMaintenancePreparation preparation,
            Clock scanClock) {
        current.writer.submitDatabase(database -> new TerritoryMaintenanceAssessmentProcessor(
                        new TerritoryMaintenanceRegistry(database, scanClock))
                .recover(
                        TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                        preparation.window().requestId(),
                        "Automatic Territory Maintenance assessment")
                .orElseThrow(() -> new IllegalStateException(
                        "Territory Maintenance Assessment Batch disappeared during recovery")))
                .whenComplete((batch, failure) ->
                        finishTerritoryMaintenanceAssessment(current, batch, failure));
    }

    private void finishTerritoryMaintenanceAssessment(
            RuntimeState current,
            TerritoryMaintenanceAssessmentBatch batch,
            Throwable failure) {
        current.territoryMaintenanceAssessmentQueued.set(false);
        if (failure != null) {
            LOGGER.error("Automatic Territory Maintenance assessment failed closed", failure);
        } else if (batch != null) {
            LOGGER.info(
                    "Automatic Territory Maintenance Cycle {} has {} Assessment(s)",
                    batch.cycle().cycleId(),
                    batch.assessments().size());
            scheduleTerritoryMaintenanceSettlement(current, batch);
        }
    }

    private void scheduleTerritoryMaintenanceSettlement(
            RuntimeState current, TerritoryMaintenanceAssessmentBatch batch) {
        if (!current.territoryMaintenanceSettlementQueued.compareAndSet(false, true)) {
            return;
        }
        Clock settlementClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> settleTerritoryMaintenance(
                        current, database, batch, settlementClock))
                .whenComplete((result, failure) -> {
                    current.territoryMaintenanceSettlementQueued.set(false);
                    if (failure != null) {
                        LOGGER.error(
                                "Automatic Territory Maintenance settlement failed closed",
                                failure);
                    } else if (result != null && result.nationCount() > 0) {
                        LOGGER.info(
                                "Automatic Territory Maintenance settled {} Nation(s): "
                                        + "{} fully funded, {} partially funded, {} unfunded",
                                result.nationCount(),
                                result.fullyFunded(),
                                result.partiallyFunded(),
                                result.unfunded());
                    }
                });
    }

    private static AutomaticMaintenanceSettlementResult settleTerritoryMaintenance(
            RuntimeState current,
            CivicDatabase database,
            TerritoryMaintenanceAssessmentBatch batch,
            Clock settlementClock) {
        String cycleRequestId = batch.cycle().requestId();
        if (!cycleRequestId.endsWith(":cycle")) {
            throw new IllegalStateException(
                    "Automatic Territory Maintenance Cycle request ID is invalid");
        }
        String requestPrefix = cycleRequestId.substring(
                0, cycleRequestId.length() - ":cycle".length());
        TerritoryMaintenancePolicyVersion policy =
                new TerritoryMaintenancePolicyRegistry(database, settlementClock)
                        .find(policyIdFromAutomaticRequest(requestPrefix))
                        .orElseThrow(() -> new IllegalStateException(
                                "Automatic Territory Maintenance Settlement policy is missing"));
        List<org.civiceconomy.nation.NationId> nations = batch.assessments().stream()
                .filter(assessment -> assessment.validity() == TerritoryFiscalValidity.PENDING)
                .map(org.civiceconomy.territory.TerritoryFiscalAssessment::nationId)
                .distinct()
                .sorted(java.util.Comparator.comparing(org.civiceconomy.nation.NationId::value))
                .toList();
        if (nations.isEmpty()) {
            return new AutomaticMaintenanceSettlementResult(0, 0, 0, 0);
        }
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        TerritoryFiscalServiceProvisioner provisioner =
                new TerritoryFiscalServiceProvisioner(authorization);
        for (org.civiceconomy.nation.NationId nationId : nations) {
            provisioner.ensureAuthorized(nationalTreasury(nationId));
        }
        var session = authorization.openSession(TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
        TerritoryMaintenancePaymentCoordinator coordinator =
                TerritoryMaintenancePaymentCoordinator.live(
                        database, session, settlementClock, current.server.overworld());
        int fullyFunded = 0;
        int partiallyFunded = 0;
        int unfunded = 0;
        RuntimeException aggregateFailure = null;
        for (org.civiceconomy.nation.NationId nationId : nations) {
            try {
                var settlement = coordinator.settleAvailable(
                                new SettleAvailableTerritoryMaintenance(
                                        TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                        requestPrefix + ":nation:" + nationId.value(),
                                        batch.cycle().cycleId(),
                                        nationId,
                                        nationalTreasury(nationId),
                                        policy.destructionBasisPoints(),
                                        "Automatic Territory Maintenance settlement"))
                        .settlement();
                if (settlement.outcome() == TerritoryMaintenanceSettlementOutcome.FULLY_FUNDED) {
                    fullyFunded++;
                } else if (settlement.outcome()
                        == TerritoryMaintenanceSettlementOutcome.PARTIALLY_FUNDED) {
                    partiallyFunded++;
                } else {
                    unfunded++;
                }
            } catch (RuntimeException failure) {
                LOGGER.error(
                        "Automatic Territory Maintenance settlement failed closed for Nation {}",
                        nationId.value(),
                        failure);
                if (aggregateFailure == null) {
                    aggregateFailure = new IllegalStateException(
                            "One or more automatic Territory Maintenance Settlements failed");
                }
                aggregateFailure.addSuppressed(failure);
            }
        }
        if (aggregateFailure != null) {
            throw aggregateFailure;
        }
        return new AutomaticMaintenanceSettlementResult(
                nations.size(), fullyFunded, partiallyFunded, unfunded);
    }

    private static AccountId nationalTreasury(org.civiceconomy.nation.NationId nationId) {
        return new AccountId("nation:" + nationId.value() + ":treasury");
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
        private final AtomicBoolean permanentDestructionRecoveryQueued = new AtomicBoolean();
        private final AtomicBoolean territoryMaintenanceAssessmentQueued = new AtomicBoolean();
        private final AtomicBoolean territoryMaintenanceSettlementQueued = new AtomicBoolean();
        private int ticksSinceCheckpoint;
        private int ticksSinceNationApplicationExpiry;
        private int ticksSinceCitizenshipReconciliation;
        private int ticksSinceTerritoryPermitCompensation;
        private int ticksSincePermanentDestructionRecovery;
        private int ticksSinceTerritoryMaintenanceAssessment;
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

    private record AutomaticNationMaintenanceContext(
            org.civiceconomy.nation.NationId nationId,
            UUID ftbTeamId,
            Capital capital,
            TerritoryFreeAllocation freeAllocation,
            Map<TerritoryClaimPosition, TerritoryMaintenanceRestorationHistory>
                    restorationHistory) {}

    private record AutomaticMaintenancePreparation(
            TerritoryMaintenancePolicyVersion policy,
            TerritoryMaintenanceCycleWindow window,
            List<AutomaticNationMaintenanceContext> nations,
            boolean recover) {
        private AutomaticMaintenancePreparation {
            nations = List.copyOf(nations);
        }
    }

    record PreparedTerritoryClaim(
            TerritoryClaimPermit permit, FreeClaimAuthorization freeClaim) {
        PreparedTerritoryClaim {
            if ((permit == null) == (freeClaim == null)) {
                throw new IllegalArgumentException(
                        "Prepared Territory Claim must contain exactly one authorization");
            }
        }
    }

    private record AutomaticMaintenanceSettlementResult(
            int nationCount, int fullyFunded, int partiallyFunded, int unfunded) {}
}
