package org.civiceconomy.platform.neoforge;

import com.mojang.authlib.GameProfile;
import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.data.CustomSaveData;
import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.data.AbstractTeamBase;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.data.ChunkTeamDataImpl;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyFiscalAccounts;
import org.civiceconomy.integration.lightmanscurrency.FiscalAccountKind;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPayments;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.nation.CreateNationApplication;
import org.civiceconomy.nation.NationApplicationId;
import org.civiceconomy.nation.NationApplicationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.JoinCitizenship;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.FtbTeamsNationProvider;
import org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.RegisterNation;
import org.civiceconomy.territory.IssueTerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPermitRegistry;
import org.civiceconomy.territory.TerritoryClaimPermitState;
import org.civiceconomy.territory.AssessTerritoryFiscalValidity;
import org.civiceconomy.territory.OpenTerritoryMaintenanceCycle;
import org.civiceconomy.territory.SuspendTerritoryMaintenance;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.civiceconomy.territory.TerritoryMaintenancePriority;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CivicServerRuntimeGameTests {
    private CivicServerRuntimeGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void playerLifecyclePersistsObservedOnlineTimeOffThread(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-runtime-test"),
                ClientInformation.createDefault());
        Path civicDirectory = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy");
        Path databaseFile = civicDirectory.resolve("civic.sqlite3");
        Path identityFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("civiceconomy_world_identity.dat");
        helper.assertTrue(Files.isRegularFile(databaseFile), "world-bound Civic SQLite file");
        helper.assertTrue(Files.isRegularFile(identityFile), "persisted Civic world identity");

        CivicServerRuntime.current().observeLogin(player);
        helper.runAfterDelay(2L, () -> {
            CivicServerRuntime.current().observeLogout(player);
            helper.succeedWhen(() -> assertPersistedInterval(helper, databaseFile, player));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fiscalAdministrationCommandsAreRegistered(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        var civic = dispatcher.getRoot().getChild("civic");
        var economy = civic.getChild("economy");
        var service = dispatcher.getRoot()
                .getChild("civic")
                .getChild("economy")
                .getChild("admin")
                .getChild("service");
        helper.assertValueEqual(
                Set.of("list", "show", "register", "grant", "revoke", "disable", "enable"),
                service.getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "trusted fiscal administration command actions");
        helper.assertValueEqual(
                Set.of("apply", "status", "cancel", "activate", "population", "role", "territory"),
                economy.getChild("nation").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Nation Application command actions");
        helper.assertValueEqual(
                Set.of("allowance", "prepare", "cancel", "restore"),
                economy.getChild("nation")
                        .getChild("territory")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Territory Claim command actions");
        if (CivicDebugWorldCommands.dedicatedStartupPermit()) {
            helper.assertValueEqual(
                    Set.of("status", "enable"),
                    civic.getChild("debug").getChildren().stream()
                            .map(node -> node.getName())
                            .collect(Collectors.toSet()),
                    "startup-permitted dedicated DEBUG WORLD command actions");
        } else {
            helper.assertTrue(
                    civic.getChild("debug") == null,
                    "dedicated GameTest server omits DEBUG WORLD writes by default");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void consoleCanRegisterFiscalServiceOffThread(GameTestHelper helper) {
        String serviceIdentity = "command-gametest-" + UUID.randomUUID();
        String requestId = "register-command-gametest-" + UUID.randomUUID();
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin service register " + serviceIdentity
                        + " civiceconomy " + requestId + " \"Command GameTest Service\""
                        + " Verify audited asynchronous registration");
        Path databaseFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        helper.succeedWhen(() -> assertFiscalServiceRegistered(
                helper,
                databaseFile,
                serviceIdentity,
                "civiceconomy",
                requestId));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void consoleSchedulesTerritoryPolicyOffThread(GameTestHelper helper) {
        String requestId = "territory-policy-command-" + UUID.randomUUID();
        String pricingRequestId = "territory-pricing-command-" + UUID.randomUUID();
        String maintenanceRequestId = "territory-maintenance-command-" + UUID.randomUUID();
        long effectiveAt = java.time.Instant.now().plusSeconds(60L).toEpochMilli();
        var server = helper.getLevel().getServer();
        Path databaseFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        long maintenanceEffectiveAt = Math.max(
                effectiveAt, nextTerritoryMaintenancePolicyBoundary(databaseFile));
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin territory policy schedule 9 4 " + effectiveAt
                        + " " + requestId + " GameTest territory policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin territory pricing schedule 100 50 " + effectiveAt
                        + " " + pricingRequestId + " GameTest territory pricing");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin territory maintenance schedule 604800000 75 15000 25 30 1209600000 6000 "
                        + maintenanceEffectiveAt + " " + maintenanceRequestId
                        + " GameTest territory maintenance");

        helper.succeedWhen(() -> {
            assertTerritoryPolicyScheduled(helper, databaseFile, requestId, effectiveAt);
            assertTerritoryPricingScheduled(
                    helper, databaseFile, pricingRequestId, effectiveAt);
            assertTerritoryMaintenancePolicyScheduled(
                    helper, databaseFile, maintenanceRequestId, maintenanceEffectiveAt);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ftbTeamHeadCanCreateNationApplicationOffThread(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-founding-test"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(player);
        UUID teamId = team.getId();
        helper.assertValueEqual(player.getUUID(), team.getOwner(), "FTB founding team head");
        helper.assertValueEqual(teamId, team.getId(), "FTB founding team ID");
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                "civic economy nation apply");
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                "civic economy nation apply");
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        helper.succeedWhen(() -> assertNationApplicationCreated(
                helper, databaseFile, teamId, player.getUUID()));
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void ftbTeamHeadCancelsNationApplicationOffThread(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-cancellation-test"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(player);
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        AtomicBoolean cancellationStarted = new AtomicBoolean();
        AtomicReference<PendingApplicationRow> observedApplication = new AtomicReference<>();

        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                "civic economy nation apply");
        helper.succeedWhen(() -> {
            PendingApplicationRow application = observedApplication.get();
            if (application == null) {
                application = pendingApplication(databaseFile, team.getId());
                if (application != null) {
                    observedApplication.compareAndSet(null, application);
                }
            }
            helper.assertTrue(application != null, "PENDING Nation Application before cancellation");
            if (cancellationStarted.compareAndSet(false, true)) {
                helper.getLevel().getServer().getCommands().performPrefixedCommand(
                        player.createCommandSourceStack().withSuppressedOutput(),
                        "civic economy nation cancel GameTest cancellation");
            }
            assertNationApplicationCancelled(
                    helper, databaseFile, application.applicationId(), player.getUUID());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void runtimeExpiresDueNationApplicationOffThread(GameTestHelper helper) {
        UUID teamId = UUID.randomUUID();
        UUID headId = UUID.randomUUID();
        NationTeam team = new NationTeam(teamId, headId, Set.of(headId));
        NationTeamDirectory teams = snapshot(team);
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        AtomicReference<NationApplicationId> applicationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        java.time.Instant appliedAt = java.time.Instant.now();

        CivicServerRuntime.current()
                .submitDatabase(database -> new NationApplicationRegistry(
                                database,
                                teams,
                                java.time.Clock.fixed(appliedAt, java.time.ZoneOffset.UTC))
                        .create(new CreateNationApplication(
                                new ServiceIdentity("civiceconomy-gametest"),
                                "runtime-expiry-" + UUID.randomUUID(),
                                teamId,
                                headId,
                                appliedAt.plusMillis(250))))
                .whenComplete((application, failure) -> {
                    if (failure == null) {
                        applicationId.set(application.applicationId());
                    } else {
                        asyncFailure.set(failure);
                    }
                });

        helper.succeedWhen(() -> {
            helper.assertTrue(asyncFailure.get() == null, "automatic expiry setup");
            NationApplicationId observed = applicationId.get();
            helper.assertTrue(observed != null, "scheduled-expiry Nation Application");
            assertNationApplicationAutomaticallyExpired(
                    helper, databaseFile, observed, headId);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void runtimeStartsCorrectionGraceForRealFtbDepartureOffThread(
            GameTestHelper helper) {
        ServerPlayer owner = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-reconciliation-owner"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(owner);
        UUID citizenId = UUID.randomUUID();
        ((AbstractTeamBase) team).addMember(citizenId, TeamRank.MEMBER);
        team.markDirty();
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        java.time.Instant joinedAt = java.time.Instant.now();
        AtomicBoolean departed = new AtomicBoolean();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "reconciliation-nation-" + UUID.randomUUID(),
                            teamSnapshot.teamId()));
                    new CitizenshipRegistry(
                                    database,
                                    java.time.Duration.ofDays(7),
                                    java.time.Clock.fixed(joinedAt, java.time.ZoneOffset.UTC))
                            .join(new JoinCitizenship(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "reconciliation-citizen-" + UUID.randomUUID(),
                                    citizenId,
                                    nation.nationId()));
                    return nation.nationId();
                })
                .whenComplete((nationId, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            ((AbstractTeamBase) team).removeMember(citizenId);
                            team.markDirty();
                            departed.set(true);
                        }));

        helper.succeedWhen(() -> {
            helper.assertTrue(asyncFailure.get() == null, "Citizenship reconciliation setup");
            helper.assertTrue(departed.get(), "real FTB member departure");
            assertCitizenshipCorrectionGraceStarted(
                    helper, databaseFile, team.getId(), citizenId);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void realFtbNationHeadGrantsOwnFiscalPermissionOffThread(
            GameTestHelper helper) {
        ServerPlayer head = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-fiscal-role-head"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(head);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        java.time.Instant joinedAt = java.time.Instant.now();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "fiscal-role-nation-" + UUID.randomUUID(),
                            teamSnapshot.teamId()));
                    new CitizenshipRegistry(
                                    database,
                                    java.time.Duration.ofDays(7),
                                    java.time.Clock.fixed(joinedAt, java.time.ZoneOffset.UTC))
                            .join(new JoinCitizenship(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "fiscal-role-head-" + UUID.randomUUID(),
                                    head.getUUID(),
                                    nation.nationId()));
                    return nation.nationId();
                })
                .whenComplete((nationId, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            try {
                                int result = helper.getLevel()
                                        .getServer()
                                        .getCommands()
                                        .getDispatcher()
                                        .execute(
                                                "civic economy nation role grant " + head.getUUID()
                                                        + " APPROVE_BUDGET GameTest appointment",
                                                head.createCommandSourceStack().withSuppressedOutput());
                                helper.assertValueEqual(
                                        1, result, "Nation fiscal role grant command result");
                                commandStarted.set(true);
                            } catch (Throwable commandFailure) {
                                asyncFailure.set(commandFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "Nation fiscal role async state"
                            : "Nation fiscal role async failure: " + failure.getMessage());
            helper.assertTrue(commandStarted.get(), "Nation fiscal role command started");
            assertNationFiscalPermissionGranted(
                    helper, databaseFile, team.getId(), head.getUUID());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void debugWorldHeadActivatesClaimedCapitalOffThread(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-activation-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(BlockPos.ZERO).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        helper.assertValueEqual(
                team.getId(),
                FtbNationTeamDirectory.live()
                        .findOwnedTeamForPlayer(player.getUUID())
                        .orElseThrow(() -> new IllegalStateException("FTB fixture owner is unresolved"))
                        .teamId(),
                "live FTB founding team ownership");
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        AtomicBoolean activationStarted = new AtomicBoolean();
        AtomicBoolean claimCleaned = new AtomicBoolean();
        AtomicReference<PendingApplicationRow> observedApplication = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();

        int applyResult;
        try {
            applyResult = helper.getLevel().getServer().getCommands().getDispatcher().execute(
                    "civic economy nation apply",
                    player.createCommandSourceStack().withSuppressedOutput());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IllegalStateException("Nation Application command rejected", failure);
        }
        helper.assertValueEqual(1, applyResult, "Nation Application command result");
        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "Nation activation async state"
                            : "Nation activation async failure: " + failure.getMessage());
            PendingApplicationRow pending = observedApplication.get();
            if (pending == null) {
                pending = pendingApplication(databaseFile, team.getId());
                if (pending != null) {
                    observedApplication.compareAndSet(null, pending);
                }
            }
            helper.assertTrue(pending != null, "PENDING Nation Application before activation");
            PendingApplicationRow application = pending;
            if (activationStarted.compareAndSet(false, true)) {
                CivicServerRuntime.current()
                        .submitDatabase(database -> {
                            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "activation-command-evidence-" + UUID.randomUUID(),
                                    player.getUUID(),
                                    application.createdAtEpochMillis(),
                                    application.createdAtEpochMillis()
                                            + java.time.Duration.ofHours(1).toMillis()));
                            return null;
                        })
                        .whenComplete((ignored, evidenceFailure) ->
                                helper.getLevel().getServer().execute(() -> {
                                    if (evidenceFailure != null) {
                                        asyncFailure.set(evidenceFailure);
                                        return;
                                    }
                                    try {
                                        claimCapital(helper, team, player);
                                        CivicDebugWorldData.get(helper.getLevel().getServer()).enable();
                                        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                                                player.createCommandSourceStack().withSuppressedOutput(),
                                                "civic economy nation activate");
                                    } catch (Throwable activationFailure) {
                                        asyncFailure.set(activationFailure);
                                    }
                                }));
            }
            AccountId treasuryAccount = activatedTreasury(
                    helper, databaseFile, application.applicationId(), player.getUUID(), player);
            helper.assertValueEqual(
                    0L,
                    LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                            .balance(treasuryAccount)
                            .minorUnits(),
                    "activated real LC National Treasury balance");
            if (claimCleaned.compareAndSet(false, true)) {
                unclaimCapital(helper, player);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void realFtbClaimConsumesPermitOnlyAfterSuccessfulMutation(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-permit-claim-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(8, 0, 8)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        AtomicReference<TerritoryClaimPermitState> observedState = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean cleanupNeeded = new AtomicBoolean();

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationTeam nationTeam = new NationTeam(
                            team.getId(), team.getOwner(), team.getMembers());
                    var nation = new NationRegistry(database, snapshot(nationTeam)).register(
                            new RegisterNation(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "permit-event-nation-" + UUID.randomUUID(),
                                    team.getId()));
                    return new TerritoryClaimPermitRegistry(
                                    database,
                                    (transactionId, nationId, amount) -> true,
                                    java.time.Clock.systemUTC())
                            .issue(new IssueTerritoryClaimPermit(
                                    new ServiceIdentity("civiceconomy-territory"),
                                    "permit-event-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    team.getId(),
                                    player.getUUID(),
                                    position.dimension().location().toString(),
                                    position.x(),
                                    position.z(),
                                    17,
                                    17,
                                    250L,
                                    UUID.randomUUID(),
                                    java.time.Instant.now().plusSeconds(120L)));
                })
                .whenComplete((permit, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                CivicServerRuntime.current().publishTerritoryClaimPermit(permit);
                                var source = player.createCommandSourceStack().withSuppressedOutput();
                                helper.assertTrue(
                                        teamData.claim(source, position, true).isSuccess(),
                                        "first simulated FTB claim");
                                helper.assertTrue(
                                        teamData.claim(source, position, true).isSuccess(),
                                        "repeated simulated FTB claim");
                                helper.assertTrue(
                                        teamData.claim(source, position, false).isSuccess(),
                                        "real FTB claim authorized by READY Permit");
                                cleanupNeeded.set(true);
                                CivicServerRuntime.current()
                                        .submitDatabase(database -> new TerritoryClaimPermitRegistry(
                                                        database,
                                                        (transactionId, nationId, amount) -> false,
                                                        java.time.Clock.systemUTC())
                                                .find(permit.permitId())
                                                .orElseThrow()
                                                .state())
                                        .whenComplete((state, inspectionFailure) -> {
                                            if (inspectionFailure != null) {
                                                asyncFailure.set(inspectionFailure);
                                            } else {
                                                observedState.set(state);
                                            }
                                        });
                            } catch (Throwable claimFailure) {
                                asyncFailure.set(claimFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "Permit event async state"
                            : "Permit event async failure: " + failure.getMessage());
            helper.assertValueEqual(
                    TerritoryClaimPermitState.CONSUMED,
                    observedState.get(),
                    "durably consumed Territory Claim Permit");
            if (cleanupNeeded.compareAndSet(true, false)) {
                ClaimedChunk claimed = manager.getChunk(position);
                if (claimed != null) {
                    claimed.unclaim(player.createCommandSourceStack(), true);
                }
            }
        });
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 1800,
            batch = "territory-force-load-guard")
    public static void suspendedTerritoryBlocksRealFtbForceLoadUntilEffectiveRestoration(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-force-load-guard-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(72, 0, 72)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        TerritoryClaimPosition civicPosition = new TerritoryClaimPosition(
                position.dimension().location().toString(), position.x(), position.z());
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        teamData.setExtraForceLoadChunks(Math.max(100, teamData.getExtraForceLoadChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        helper.assertTrue(
                teamData.claim(player.createCommandSourceStack(), position, false).isSuccess(),
                "force-load guard Claim fixture");

        java.time.Instant now = java.time.Instant.now();
        ServiceIdentity maintenanceService =
                new ServiceIdentity("force-load-gametest-" + UUID.randomUUID());
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<java.time.Instant> restoredCycleStart = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean restorationCommitted = new AtomicBoolean();

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationTeam nationTeam = new NationTeam(
                            team.getId(), team.getOwner(), team.getMembers());
                    var nation = new NationRegistry(database, snapshot(nationTeam)).register(
                            new RegisterNation(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "force-load-guard-nation-" + UUID.randomUUID(),
                                    team.getId()));
                    nationId.set(nation.nationId());
                    TerritoryMaintenanceRegistry maintenance =
                            new TerritoryMaintenanceRegistry(database);
                    long suspendedStartMillis =
                            now.minus(java.time.Duration.ofHours(25L)).toEpochMilli() - 4L;
                    while (true) {
                        var occupiedAtSuspension =
                                database.territoryMaintenanceCycleAt(suspendedStartMillis);
                        if (occupiedAtSuspension != null) {
                            suspendedStartMillis =
                                    occupiedAtSuspension.startsAtEpochMillis() - 4L;
                            continue;
                        }
                        var occupiedAtRestoration =
                                database.territoryMaintenanceCycleAt(suspendedStartMillis + 2L);
                        if (occupiedAtRestoration != null) {
                            suspendedStartMillis =
                                    occupiedAtRestoration.startsAtEpochMillis() - 4L;
                            continue;
                        }
                        break;
                    }
                    java.time.Instant suspendedCycleStart =
                            java.time.Instant.ofEpochMilli(suspendedStartMillis);
                    java.time.Instant suspendedCycleEnd = suspendedCycleStart.plusMillis(1L);
                    restoredCycleStart.set(suspendedCycleEnd.plusMillis(1L));
                    var suspendedCycle = maintenance.openCycle(
                            new OpenTerritoryMaintenanceCycle(
                                    maintenanceService,
                                    "force-load-guard-cycle-" + UUID.randomUUID(),
                                    suspendedCycleStart,
                                    suspendedCycleEnd));
                    maintenance.assess(new AssessTerritoryFiscalValidity(
                            maintenanceService,
                            "force-load-guard-assessment-" + UUID.randomUUID(),
                            suspendedCycle.cycleId(),
                            nation.nationId(),
                            team.getId(),
                            civicPosition.dimensionId(),
                            civicPosition.chunkX(),
                            civicPosition.chunkZ(),
                            10L,
                            TerritoryMaintenancePriority.ORDINARY,
                            "Post-grace force-load guard GameTest"));
                    maintenance.suspend(new SuspendTerritoryMaintenance(
                            maintenanceService,
                            "force-load-guard-settlement-" + UUID.randomUUID(),
                            suspendedCycle.cycleId(),
                            nation.nationId(),
                            "Post-grace force-load guard GameTest"));
                    return nation.nationId();
                })
                .whenComplete((ignored, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                            } else {
                                CivicServerRuntime.current()
                                        .refreshTerritoryForceLoadRestrictions();
                            }
                        }));

        CivicServerRuntime runtime = CivicServerRuntime.current();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "force-load guard async state"
                                    : "force-load guard async failure: " + failure.getMessage());
                    helper.assertTrue(
                            runtime.territoryForceLoadRestrictionsReady(),
                            "force-load Restriction mirror ready");
                    helper.assertTrue(
                            runtime.territoryForceLoadBlocked(team.getId(), civicPosition),
                            "exact post-grace suspended Claim mirrored");
                })
                .thenExecute(() -> {
                    helper.assertFalse(
                            teamData.forceLoad(
                                            player.createCommandSourceStack()
                                                    .withSuppressedOutput(),
                                            position,
                                            false)
                                    .isSuccess(),
                            "real FTB BEFORE_LOAD rejects post-grace suspended Territory");
                    helper.assertFalse(
                            manager.getChunk(position).isForceLoaded(),
                            "rejected force-load does not mutate FTB state");
                    runtime.submitDatabase(database -> {
                                TerritoryMaintenanceRegistry maintenance =
                                        new TerritoryMaintenanceRegistry(database);
                                var restoredCycle = maintenance.openCycle(
                                        new OpenTerritoryMaintenanceCycle(
                                                maintenanceService,
                                                "force-load-restored-cycle-" + UUID.randomUUID(),
                                                restoredCycleStart.get(),
                                                restoredCycleStart.get().plusMillis(1L)));
                                maintenance.assess(new AssessTerritoryFiscalValidity(
                                        maintenanceService,
                                        "force-load-restored-assessment-" + UUID.randomUUID(),
                                        restoredCycle.cycleId(),
                                        nationId.get(),
                                        team.getId(),
                                        civicPosition.dimensionId(),
                                        civicPosition.chunkX(),
                                        civicPosition.chunkZ(),
                                        0L,
                                        TerritoryMaintenancePriority.ORDINARY,
                                        "Effective restoration mirror GameTest"));
                                maintenance.settleZeroCostAssessments(
                                        new SuspendTerritoryMaintenance(
                                                maintenanceService,
                                                "force-load-restored-settlement-" + UUID.randomUUID(),
                                                restoredCycle.cycleId(),
                                                nationId.get(),
                                                "Effective restoration mirror GameTest"));
                                return null;
                            })
                            .whenComplete((ignored, restorationFailure) ->
                                    helper.getLevel().getServer().execute(() -> {
                                        if (restorationFailure != null) {
                                            asyncFailure.set(restorationFailure);
                                        } else {
                                            restorationCommitted.set(true);
                                            runtime.refreshTerritoryForceLoadRestrictions();
                                        }
                                    }));
                })
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "restoration async state"
                                    : "restoration async failure: " + failure.getMessage());
                    helper.assertTrue(restorationCommitted.get(), "effective restoration committed");
                    helper.assertFalse(
                            runtime.territoryForceLoadBlocked(team.getId(), civicPosition),
                            "effective restoration removes exact Restriction");
                })
                .thenExecute(() -> {
                    helper.assertTrue(
                            teamData.forceLoad(
                                            player.createCommandSourceStack().withSuppressedOutput(),
                                            position,
                                            false)
                                    .isSuccess(),
                            "authorized player can manually re-enable FTB force-load after restoration");
                    helper.assertTrue(
                            manager.getChunk(position).isForceLoaded(),
                            "restored Claim force-load requested");
                    helper.assertTrue(
                            teamData.unForceLoad(player.createCommandSourceStack(), position, false)
                                    .isSuccess(),
                            "force-load guard cleanup unload");
                    manager.getChunk(position).unclaim(player.createCommandSourceStack(), true);
                })
                .thenSucceed();
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 1800,
            batch = "territory-player-restoration-command")
    public static void playerRestoreCommandPaysRealLcAndReleasesExactFtbRestrictionExactlyOnce(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-player-restoration-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(88, 0, 88)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String requestId = "player-territory-restoration-" + UUID.randomUUID();
        UUID sourceAssessmentId = UUID.randomUUID();
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        TerritoryClaimPosition civicPosition = new TerritoryClaimPosition(
                position.dimension().location().toString(), position.x(), position.z());
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        teamData.setExtraForceLoadChunks(Math.max(100, teamData.getExtraForceLoadChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        helper.assertTrue(
                teamData.claim(
                                player.createCommandSourceStack().withSuppressedOutput(),
                                position,
                                false)
                        .isSuccess(),
                "out-of-Cycle Restoration FTB Claim fixture");
        helper.assertFalse(
                manager.getChunk(position).isForceLoaded(),
                "Restoration fixture does not begin force-loaded");

        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Long> issuanceAfterSeed = new AtomicReference<>();
        AtomicReference<Long> publicFundBefore = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean replayStarted = new AtomicBoolean();
        AtomicBoolean replayCompleted = new AtomicBoolean();
        AtomicBoolean cleanupDone = new AtomicBoolean();
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock =
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);

        CivicServerRuntime runtime = CivicServerRuntime.current();
        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    java.time.Instant appliedAt = now.minus(java.time.Duration.ofHours(2L));
                    var application = new NationApplicationRegistry(
                                    database,
                                    snapshot(teamSnapshot),
                                    java.time.Clock.fixed(appliedAt, java.time.ZoneOffset.UTC))
                            .create(new CreateNationApplication(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "player-restoration-application-" + UUID.randomUUID(),
                                    team.getId(),
                                    player.getUUID(),
                                    now.plus(java.time.Duration.ofDays(1L))));
                    new OnlineTimeLedger(database).record(new RecordOnlineTime(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "player-restoration-evidence-" + UUID.randomUUID(),
                            player.getUUID(),
                            appliedAt.toEpochMilli(),
                            now.toEpochMilli()));
                    var activated = new org.civiceconomy.nation.NationActivationCoordinator(
                                    database,
                                    (activatedNationId, treasuryAccountId) -> {},
                                    org.civiceconomy.nation.NationFoundingPolicy.debugWorld(
                                            2,
                                            java.time.Duration.ofDays(30L),
                                            java.time.Duration.ofDays(7L)),
                                    setupClock)
                            .activate(new org.civiceconomy.nation.ActivateNationApplication(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "player-restoration-activation-" + UUID.randomUUID(),
                                    application.applicationId(),
                                    new org.civiceconomy.nation.Capital(
                                            civicPosition.dimensionId(),
                                            civicPosition.chunkX(),
                                            civicPosition.chunkZ()),
                                    "Formal GameTest activation for player Restoration"));
                    var nation = activated.nation();
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, java.time.Duration.ofDays(7L), setupClock);
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    new NationFiscalAuthorityRegistry(database, provider, setupClock)
                            .grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "player-restoration-authority-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    player.getUUID(),
                                    player.getUUID(),
                                    NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                                    "Real player out-of-Cycle Restoration GameTest"));
                    java.time.Instant policyEffectiveAt = java.time.Instant.now().minusMillis(1L);
                    java.time.Duration cycleDuration = java.time.Duration.ofDays(7L);
                    java.time.Instant nextPolicyEffectiveAt =
                            policyEffectiveAt.plus(cycleDuration).minusMillis(1L);
                    database.scheduleTerritoryFreeAllocationPolicy(
                            UUID.randomUUID(),
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-free-policy-" + UUID.randomUUID(),
                            "gametest",
                            0,
                            0,
                            nextPolicyEffectiveAt.toEpochMilli(),
                            "No free Territory in player Restoration GameTest",
                            policyEffectiveAt.toEpochMilli());
                    database.scheduleTerritoryMaintenancePolicy(
                            UUID.randomUUID(),
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-maintenance-policy-" + UUID.randomUUID(),
                            "gametest",
                            cycleDuration.toMillis(),
                            100L,
                            15_000,
                            0L,
                            30L,
                            java.time.Duration.ofDays(14L).toMillis(),
                            6_000,
                            policyEffectiveAt.toEpochMilli(),
                            "Exact real-LC player Restoration policy",
                            policyEffectiveAt.toEpochMilli());
                    database.scheduleTerritoryMaintenancePolicy(
                            UUID.randomUUID(),
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-next-maintenance-policy-" + UUID.randomUUID(),
                            "gametest",
                            cycleDuration.toMillis(),
                            100L,
                            15_000,
                            0L,
                            30L,
                            java.time.Duration.ofDays(14L).toMillis(),
                            6_000,
                            nextPolicyEffectiveAt.toEpochMilli(),
                            "Exact next-Cycle real-LC player Restoration policy",
                            policyEffectiveAt.toEpochMilli());
                    TerritoryMaintenanceRegistry maintenance =
                            new TerritoryMaintenanceRegistry(database, setupClock);
                    long suspendedStartMillis =
                            now.minus(java.time.Duration.ofHours(25L)).toEpochMilli();
                    while (true) {
                        var occupiedAtStart =
                                database.territoryMaintenanceCycleAt(suspendedStartMillis);
                        if (occupiedAtStart != null) {
                            suspendedStartMillis = occupiedAtStart.startsAtEpochMillis() - 4L;
                            continue;
                        }
                        var occupiedAtEnd =
                                database.territoryMaintenanceCycleAt(suspendedStartMillis + 1L);
                        if (occupiedAtEnd != null) {
                            suspendedStartMillis = occupiedAtEnd.startsAtEpochMillis() - 4L;
                            continue;
                        }
                        break;
                    }
                    var suspendedCycle = maintenance.openCycle(
                            new OpenTerritoryMaintenanceCycle(
                                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                            .SERVICE_IDENTITY,
                                    "player-restoration-cycle-" + UUID.randomUUID(),
                                    java.time.Instant.ofEpochMilli(suspendedStartMillis),
                                    java.time.Instant.ofEpochMilli(suspendedStartMillis + 1L)));
                    database.assessTerritoryFiscalValidity(
                            sourceAssessmentId,
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-assessment-" + UUID.randomUUID(),
                            suspendedCycle.cycleId(),
                            nation.nationId().value(),
                            team.getId(),
                            civicPosition.dimensionId(),
                            civicPosition.chunkX(),
                            civicPosition.chunkZ(),
                            100L,
                            TerritoryMaintenancePriority.CAPITAL.name(),
                            "Latest suspended target for real player Restoration",
                            suspendedStartMillis);
                    maintenance.suspend(new SuspendTerritoryMaintenance(
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY,
                            "player-restoration-suspension-" + UUID.randomUUID(),
                            suspendedCycle.cycleId(),
                            nation.nationId(),
                            "Insufficient funds before player Restoration"));
                    long issuanceBefore = database.cumulativeNetIssuanceMinorUnits();
                    database.confirmMonetarySupplyChange(
                            UUID.randomUUID(),
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-issuance-" + UUID.randomUUID(),
                            "ISSUANCE",
                            130L,
                            "mint-batch:player-restoration:" + UUID.randomUUID(),
                            "Seed real player Restoration destruction capacity",
                            now.toEpochMilli(),
                            Math.addExact(issuanceBefore, 10_000L));
                    issuanceAfterSeed.set(database.cumulativeNetIssuanceMinorUnits());
                    return nation.nationId();
                })
                .whenComplete((registeredNationId, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                fundTreasury(
                                        helper,
                                        registeredNationId,
                                        "Player Restoration GameTest Treasury",
                                        130L);
                                publicFundBefore.set(
                                        LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                                .balance(org.civiceconomy.integration
                                                        .lightmanscurrency
                                                        .LightmansCurrencyPublicMaintenanceFundProvisioner
                                                        .ACCOUNT_ID)
                                                .minorUnits());
                                setupReady.set(true);
                                runtime.refreshTerritoryForceLoadRestrictions();
                            } catch (Throwable preparationFailure) {
                                asyncFailure.set(preparationFailure);
                            }
                        }));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "player Restoration setup state"
                                    : "player Restoration setup failure: "
                                            + failure.getMessage());
                    helper.assertTrue(setupReady.get(), "player Restoration setup complete");
                    helper.assertTrue(
                            runtime.territoryForceLoadBlocked(team.getId(), civicPosition),
                            "latest suspended target is force-load restricted before command");
                })
                .thenExecute(() -> {
                    try {
                        int result = helper.getLevel()
                                .getServer()
                                .getCommands()
                                .getDispatcher()
                                .execute(
                                        "civic economy nation territory restore " + requestId,
                                        player.createCommandSourceStack().withSuppressedOutput());
                        helper.assertValueEqual(1, result, "Territory Restoration command result");
                        commandStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                })
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "player Restoration command state"
                                    : "player Restoration command failure: "
                                            + failure.getMessage());
                    helper.assertTrue(commandStarted.get(), "Territory Restoration command started");
                    TerritoryRestorationRow restoration =
                            territoryRestorationByRequest(databaseFile, requestId);
                    helper.assertTrue(restoration != null, "persisted player Restoration");
                    helper.assertValueEqual(
                            "CIVIC_COMMITTED", restoration.state(), "committed Restoration state");
                    helper.assertValueEqual(
                            sourceAssessmentId,
                            restoration.sourceAssessmentId(),
                            "exact source suspended Assessment");
                    helper.assertValueEqual(100L, restoration.prepayment(), "next-Cycle prepayment");
                    helper.assertValueEqual(30L, restoration.fee(), "Restoration fee");
                    helper.assertValueEqual(130L, restoration.total(), "Restoration total");
                    helper.assertValueEqual(
                            "SUSPENDED",
                            restoration.sourceValidity(),
                            "source Assessment remains immutable");
                    helper.assertFalse(
                            runtime.territoryForceLoadBlocked(team.getId(), civicPosition),
                            "committed exact Restoration releases force-load Restriction");
                })
                .thenExecute(() -> {
                    AccountId treasury = new AccountId(
                            "nation:" + nationId.get().value() + ":treasury");
                    var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
                    helper.assertValueEqual(
                            0L, accounts.balance(treasury).minorUnits(), "Restoration Treasury balance");
                    helper.assertValueEqual(
                            publicFundBefore.get() + 52L,
                            accounts.balance(org.civiceconomy.integration.lightmanscurrency
                                            .LightmansCurrencyPublicMaintenanceFundProvisioner
                                            .ACCOUNT_ID)
                                    .minorUnits(),
                            "Restoration Public Maintenance Fund delta");
                    helper.assertValueEqual(
                            issuanceAfterSeed.get() - 78L,
                            cumulativeNetIssuance(databaseFile),
                            "Restoration permanent-destruction delta");
                    helper.assertFalse(
                            manager.getChunk(position).isForceLoaded(),
                            "Restoration does not automatically enable FTB force-load");
                    replayStarted.set(true);
                    runtime.restoreTerritory(
                                    teamSnapshot,
                                    player.getUUID(),
                                    requestId,
                                    civicPosition.dimensionId(),
                                    civicPosition.chunkX(),
                                    civicPosition.chunkZ())
                            .whenComplete((replay, replayFailure) -> {
                                if (replayFailure != null) {
                                    asyncFailure.set(replayFailure);
                                } else {
                                    replayCompleted.set(true);
                                }
                            });
                })
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "player Restoration replay state"
                                    : "player Restoration replay failure: "
                                            + failure.getMessage());
                    helper.assertTrue(replayStarted.get(), "Territory Restoration replay started");
                    helper.assertTrue(replayCompleted.get(), "Territory Restoration replay completed");
                    AccountId treasury = new AccountId(
                            "nation:" + nationId.get().value() + ":treasury");
                    var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
                    helper.assertValueEqual(
                            0L,
                            accounts.balance(treasury).minorUnits(),
                            "Restoration replay does not debit Treasury twice");
                    helper.assertValueEqual(
                            publicFundBefore.get() + 52L,
                            accounts.balance(org.civiceconomy.integration.lightmanscurrency
                                            .LightmansCurrencyPublicMaintenanceFundProvisioner
                                            .ACCOUNT_ID)
                                    .minorUnits(),
                            "Restoration replay does not credit public fund twice");
                    helper.assertValueEqual(
                            issuanceAfterSeed.get() - 78L,
                            cumulativeNetIssuance(databaseFile),
                            "Restoration replay does not destroy money twice");
                })
                .thenExecute(() -> {
                    if (cleanupDone.compareAndSet(false, true)) {
                        ClaimedChunk claimed = manager.getChunk(position);
                        if (claimed != null) {
                            claimed.unclaim(player.createCommandSourceStack(), true);
                        }
                        clearFiscalAccountAmount(
                                helper,
                                org.civiceconomy.integration.lightmanscurrency
                                        .LightmansCurrencyPublicMaintenanceFundProvisioner
                                        .ACCOUNT_ID,
                                52L);
                    }
                })
                .thenSucceed();
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 500,
            batch = "territory-player-paid-claim")
    public static void playerPrepareCommandPaysRealLcAndAuthorizesRealFtbClaim(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-paid-claim-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(24, 0, 24)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String requestId = "player-paid-claim-" + UUID.randomUUID();
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        ChunkDimPos seedPosition = paidClaimSeedPosition(position);
        helper.assertTrue(
                teamData.claim(
                                player.createCommandSourceStack().withSuppressedOutput(),
                                seedPosition,
                                false)
                        .isSuccess(),
                "paid claim pricing seed");
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean claimStarted = new AtomicBoolean();
        AtomicBoolean cleanupDone = new AtomicBoolean();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(
                now, java.time.ZoneOffset.UTC);

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "paid-claim-nation-" + UUID.randomUUID(),
                            team.getId()));
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, java.time.Duration.ofDays(7), setupClock);
                    citizenships.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "paid-claim-citizenship-" + UUID.randomUUID(),
                            player.getUUID(),
                            nation.nationId()));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    new NationFiscalAuthorityRegistry(database, provider, setupClock)
                            .grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "paid-claim-authority-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    player.getUUID(),
                                    player.getUUID(),
                                    NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                                    "Real player Territory preparation GameTest"));
                    database.scheduleTerritoryFreeAllocationPolicy(
                            UUID.randomUUID(),
                            "civiceconomy-gametest",
                            "paid-claim-free-policy-" + UUID.randomUUID(),
                            "gametest",
                            0,
                            0,
                            now.minusSeconds(2L).toEpochMilli(),
                            "No free claims in paid command GameTest",
                            now.minusSeconds(3L).toEpochMilli());
                    database.scheduleTerritoryExpansionPricingPolicy(
                            UUID.randomUUID(),
                            "civiceconomy-gametest",
                            "paid-claim-pricing-" + UUID.randomUUID(),
                            "gametest",
                            250L,
                            0L,
                            now.minusSeconds(2L).toEpochMilli(),
                            "Flat paid claim GameTest pricing",
                            now.minusSeconds(3L).toEpochMilli());
                    return nation.nationId();
                })
                .whenComplete((registeredNationId, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                AccountId treasury = new AccountId(
                                        "nation:" + registeredNationId.value() + ":treasury");
                                var accounts = LightmansCurrencyFiscalAccounts.forLevel(
                                        helper.getLevel());
                                accounts.create(
                                        treasury,
                                        FiscalAccountKind.NATIONAL_TREASURY,
                                        "Paid Claim GameTest Treasury");
                                UUID fundingPlayerId = UUID.randomUUID();
                                BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
                                var funding = bankData.getAccount(fundingPlayerId);
                                funding.getMoneyStorage().clear();
                                helper.assertTrue(
                                        BankAPI.getApi().BankDepositFromServer(
                                                funding,
                                                CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1_000L)),
                                        "seed paid claim LC funding account");
                                LightmansCurrencyPayments.live(helper.getLevel()).apply(
                                        new ExternalPayment(
                                                UUID.randomUUID(),
                                                new AccountId("player:" + fundingPlayerId),
                                                treasury,
                                                MoneyAmount.ofMinorUnits(1_000L)));
                                bankData.deleteAccount(fundingPlayerId);
                                int result = helper.getLevel()
                                        .getServer()
                                        .getCommands()
                                        .getDispatcher()
                                        .execute(
                                                "civic economy nation territory prepare "
                                                        + requestId,
                                                player.createCommandSourceStack()
                                                        .withSuppressedOutput());
                                helper.assertValueEqual(
                                        1, result, "Territory preparation command result");
                                commandStarted.set(true);
                            } catch (Throwable commandFailure) {
                                asyncFailure.set(commandFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "paid claim async state"
                            : "paid claim async failure: " + failure.getMessage());
            helper.assertTrue(commandStarted.get(), "Territory preparation command started");
            var permit = territoryPermitByRequest(databaseFile, requestId + ":permit");
            helper.assertTrue(permit != null, "READY Permit from player preparation command");
            AccountId treasury = new AccountId(
                    "nation:" + nationId.get().value() + ":treasury");
            var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
            helper.assertValueEqual(
                    750L, accounts.balance(treasury).minorUnits(), "paid claim Treasury balance");
            helper.assertValueEqual(
                    250L,
                    accounts.balance(org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .CLEARING_ACCOUNT_ID)
                            .minorUnits(),
                    "paid claim clearing balance");
            if (claimStarted.compareAndSet(false, true)) {
                var claim = teamData.claim(
                        player.createCommandSourceStack().withSuppressedOutput(),
                        position,
                        false);
                helper.assertTrue(claim.isSuccess(), "real paid FTB claim");
            }
            var consumed = territoryPermitByRequest(databaseFile, requestId + ":permit");
            helper.assertValueEqual(
                    TerritoryClaimPermitState.CONSUMED,
                    consumed.state(),
                    "player-prepared Permit consumed after real claim");
            if (cleanupDone.compareAndSet(false, true)) {
                ClaimedChunk claimed = manager.getChunk(position);
                if (claimed != null) {
                    claimed.unclaim(player.createCommandSourceStack(), true);
                }
                ClaimedChunk seed = manager.getChunk(seedPosition);
                if (seed != null) {
                    seed.unclaim(player.createCommandSourceStack(), true);
                }
                clearFiscalAccount(
                        helper,
                        org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                .CLEARING_ACCOUNT_ID);
            }
        });
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 500,
            batch = "territory-player-cancelled-claim")
    public static void playerCancelCommandRefundsRealLcAndRemovesClaimAuthorization(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-cancel-claim-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(40, 0, 40)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String prepareRequestId = "player-cancel-prepare-" + UUID.randomUUID();
        String cancelRequestId = "player-cancel-refund-" + UUID.randomUUID();
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        ChunkDimPos seedPosition = paidClaimSeedPosition(position);
        helper.assertTrue(
                teamData.claim(
                                player.createCommandSourceStack().withSuppressedOutput(),
                                seedPosition,
                                false)
                        .isSuccess(),
                "cancelled claim pricing seed");
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean prepareStarted = new AtomicBoolean();
        AtomicBoolean cancelStarted = new AtomicBoolean();
        AtomicBoolean claimChecked = new AtomicBoolean();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(
                now, java.time.ZoneOffset.UTC);

        CivicServerRuntime.current()
                .submitDatabase(database -> setupPaidClaimNation(
                        database, teamSnapshot, player.getUUID(), now, setupClock))
                .whenComplete((registeredNationId, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                fundTreasury(
                                        helper,
                                        registeredNationId,
                                        "Cancelled Claim GameTest Treasury");
                                int result = helper.getLevel()
                                        .getServer()
                                        .getCommands()
                                        .getDispatcher()
                                        .execute(
                                                "civic economy nation territory prepare "
                                                        + prepareRequestId,
                                                player.createCommandSourceStack()
                                                        .withSuppressedOutput());
                                helper.assertValueEqual(
                                        1, result, "cancellable Territory preparation command result");
                                prepareStarted.set(true);
                            } catch (Throwable commandFailure) {
                                asyncFailure.set(commandFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "cancelled claim async state"
                            : "cancelled claim async failure: " + failure.getMessage());
            helper.assertTrue(prepareStarted.get(), "cancellable preparation command started");
            TerritoryPermitRow permit = territoryPermitByRequest(
                    databaseFile, prepareRequestId + ":permit");
            helper.assertTrue(permit != null, "READY Permit before player cancellation");
            if (cancelStarted.compareAndSet(false, true)) {
                try {
                    int result = helper.getLevel()
                            .getServer()
                            .getCommands()
                            .getDispatcher()
                            .execute(
                                    "civic economy nation territory cancel "
                                            + permit.permitId() + " " + cancelRequestId
                                            + " Player changed plans",
                                    player.createCommandSourceStack().withSuppressedOutput());
                    helper.assertValueEqual(
                            1, result, "Territory cancellation command result");
                } catch (Throwable cancellationFailure) {
                    asyncFailure.set(cancellationFailure);
                }
            }
            TerritoryPermitRow cancelled = territoryPermitByRequest(
                    databaseFile, prepareRequestId + ":permit");
            helper.assertValueEqual(
                    TerritoryClaimPermitState.CANCELLED,
                    cancelled.state(),
                    "player-cancelled Permit state");
            AccountId treasury = new AccountId(
                    "nation:" + nationId.get().value() + ":treasury");
            var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
            helper.assertValueEqual(
                    1_000L,
                    accounts.balance(treasury).minorUnits(),
                    "cancelled claim Treasury refund");
            helper.assertValueEqual(
                    0L,
                    accounts.balance(org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .CLEARING_ACCOUNT_ID)
                            .minorUnits(),
                    "cancelled claim clearing balance");
            if (claimChecked.compareAndSet(false, true)) {
                var claim = teamData.claim(
                        player.createCommandSourceStack().withSuppressedOutput(),
                        position,
                        false);
                helper.assertFalse(
                        claim.isSuccess(), "cancelled Permit cannot authorize a real FTB claim");
                ClaimedChunk seed = manager.getChunk(seedPosition);
                if (seed != null) {
                    seed.unclaim(player.createCommandSourceStack(), true);
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void playerPrepareCommandAuthorizesFreeClaimWithoutFiscalMovement(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-free-claim-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(56, 0, 56)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String requestId = "player-free-claim-" + UUID.randomUUID();
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean claimed = new AtomicBoolean();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(
                now, java.time.ZoneOffset.UTC);

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    var registeredNationId = setupPaidClaimNation(
                            database, teamSnapshot, player.getUUID(), now, setupClock);
                    database.scheduleTerritoryFreeAllocationPolicy(
                            UUID.randomUUID(),
                            "civiceconomy-gametest",
                            "free-claim-policy-" + UUID.randomUUID(),
                            "gametest",
                            1,
                            0,
                            now.minusSeconds(1L).toEpochMilli(),
                            "One free claim for real FTB GameTest",
                            now.minusSeconds(2L).toEpochMilli());
                    return registeredNationId;
                })
                .whenComplete((registeredNationId, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                int result = helper.getLevel()
                                        .getServer()
                                        .getCommands()
                                        .getDispatcher()
                                        .execute(
                                                "civic economy nation territory prepare "
                                                        + requestId,
                                                player.createCommandSourceStack()
                                                        .withSuppressedOutput());
                                helper.assertValueEqual(
                                        1, result, "Free Claim preparation command result");
                                commandStarted.set(true);
                            } catch (Throwable commandFailure) {
                                asyncFailure.set(commandFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "free claim async state"
                            : "free claim async failure: " + failure.getMessage());
            helper.assertTrue(commandStarted.get(), "Free Claim preparation command started");
            helper.assertTrue(
                    territoryPermitByRequest(databaseFile, requestId + ":permit") == null,
                    "Free Claim creates no paid Permit row");
            helper.assertValueEqual(
                    0,
                    territoryFiscalRequestCount(databaseFile, requestId),
                    "Free Claim creates no fiscal request rows");
            try {
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel()).balance(
                        new AccountId("nation:" + nationId.get().value() + ":treasury"));
                throw new IllegalStateException(
                        "Free Claim unexpectedly created a National Treasury account");
            } catch (IllegalArgumentException expectedMissingTreasury) {
                // Expected: Free Claim preparation has no fiscal-account side effect.
            }
            var source = player.createCommandSourceStack().withSuppressedOutput();
            helper.assertTrue(
                    teamData.claim(source, position, true).isSuccess(),
                    "simulated Free Claim authorization");
            helper.assertTrue(
                    teamData.claim(source, position, true).isSuccess(),
                    "repeated simulated Free Claim authorization");
            if (claimed.compareAndSet(false, true)) {
                helper.assertTrue(
                        teamData.claim(source, position, false).isSuccess(),
                        "real FTB claim through Free Claim Authorization");
                ClaimedChunk claimedChunk = manager.getChunk(position);
                helper.assertTrue(claimedChunk != null, "real free FTB ownership");
                claimedChunk.unclaim(source, true);
            }
        });
    }

    private static void assertPersistedInterval(GameTestHelper helper, Path databaseFile, ServerPlayer player) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*), COALESCE(SUM(ended_at_epoch_millis - started_at_epoch_millis), 0)
                        FROM online_time_interval
                        WHERE player_id = ?
                        """)) {
            query.setString(1, player.getUUID().toString());
            try (var result = query.executeQuery()) {
                helper.assertValueEqual(1, result.getInt(1), "persisted online-time interval count");
                helper.assertTrue(result.getLong(2) > 0L, "persisted online-time duration");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Civic runtime GameTest database", failure);
        }
    }

    private static TerritoryPermitRow territoryPermitByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT permit_id, state
                        FROM territory_claim_permit
                        WHERE service_identity = ? AND request_id = ?
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new TerritoryPermitRow(
                                UUID.fromString(result.getString(1)),
                                TerritoryClaimPermitState.valueOf(result.getString(2)))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect player-prepared Territory Claim Permit", failure);
        }
    }

    private static int territoryFiscalRequestCount(Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT
                          (SELECT COUNT(*) FROM fiscal_reservation
                           WHERE service_identity = ? AND request_id = ?)
                          +
                          (SELECT COUNT(*) FROM payment_transaction
                           WHERE service_identity = ? AND request_id = ?)
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId + ":reserve");
            query.setString(
                    3,
                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(4, requestId + ":payment");
            try (var result = query.executeQuery()) {
                return result.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Free Claim fiscal side effects", failure);
        }
    }

    private static org.civiceconomy.nation.NationId setupPaidClaimNation(
            org.civiceconomy.persistence.CivicDatabase database,
            NationTeam team,
            UUID playerId,
            java.time.Instant now,
            java.time.Clock setupClock) {
        NationRegistry nations = new NationRegistry(database, snapshot(team));
        var nation = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy-gametest"),
                "paid-claim-nation-" + UUID.randomUUID(),
                team.teamId()));
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, java.time.Duration.ofDays(7), setupClock);
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy-gametest"),
                "paid-claim-citizenship-" + UUID.randomUUID(),
                playerId,
                nation.nationId()));
        var provider = new FtbTeamsNationProvider(
                nations,
                citizenships,
                new CitizenshipCorrectionGraceRegistry(database, setupClock),
                snapshot(team));
        new NationFiscalAuthorityRegistry(database, provider, setupClock)
                .grant(new GrantNationFiscalPermission(
                        new ServiceIdentity("civiceconomy-gametest"),
                        "paid-claim-authority-" + UUID.randomUUID(),
                        nation.nationId(),
                        playerId,
                        playerId,
                        NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                        "Real player Territory preparation GameTest"));
        database.scheduleTerritoryFreeAllocationPolicy(
                UUID.randomUUID(),
                "civiceconomy-gametest",
                "paid-claim-free-policy-" + UUID.randomUUID(),
                "gametest",
                0,
                0,
                now.minusSeconds(2L).toEpochMilli(),
                "No free claims in paid command GameTest",
                now.minusSeconds(3L).toEpochMilli());
        database.scheduleTerritoryExpansionPricingPolicy(
                UUID.randomUUID(),
                "civiceconomy-gametest",
                "paid-claim-pricing-" + UUID.randomUUID(),
                "gametest",
                250L,
                0L,
                now.minusSeconds(2L).toEpochMilli(),
                "Flat paid claim GameTest pricing",
                now.minusSeconds(3L).toEpochMilli());
        return nation.nationId();
    }

    private static void fundTreasury(
            GameTestHelper helper,
            org.civiceconomy.nation.NationId nationId,
            String displayName) {
        fundTreasury(helper, nationId, displayName, 1_000L);
    }

    private static void fundTreasury(
            GameTestHelper helper,
            org.civiceconomy.nation.NationId nationId,
            String displayName,
            long amountMinorUnits) {
        AccountId treasury = new AccountId(
                "nation:" + nationId.value() + ":treasury");
        var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, displayName);
        UUID fundingPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        var funding = bankData.getAccount(fundingPlayerId);
        funding.getMoneyStorage().clear();
        if (!BankAPI.getApi().BankDepositFromServer(
                funding, CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, amountMinorUnits))) {
            throw new IllegalStateException("Unable to seed paid claim LC funding account");
        }
        LightmansCurrencyPayments.live(helper.getLevel()).apply(new ExternalPayment(
                UUID.randomUUID(),
                new AccountId("player:" + fundingPlayerId),
                treasury,
                MoneyAmount.ofMinorUnits(amountMinorUnits)));
        bankData.deleteAccount(fundingPlayerId);
    }

    private static void clearFiscalAccount(GameTestHelper helper, AccountId accountId) {
        var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        MoneyAmount balance = accounts.balance(accountId);
        if (balance.equals(MoneyAmount.ZERO)) {
            return;
        }
        UUID sinkPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        bankData.getAccount(sinkPlayerId).getMoneyStorage().clear();
        LightmansCurrencyPayments.live(helper.getLevel()).apply(new ExternalPayment(
                UUID.randomUUID(),
                accountId,
                new AccountId("player:" + sinkPlayerId),
                balance));
        bankData.deleteAccount(sinkPlayerId);
    }

    private static void clearFiscalAccountAmount(
            GameTestHelper helper, AccountId accountId, long amountMinorUnits) {
        if (amountMinorUnits == 0L) {
            return;
        }
        UUID sinkPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        bankData.getAccount(sinkPlayerId).getMoneyStorage().clear();
        LightmansCurrencyPayments.live(helper.getLevel()).apply(new ExternalPayment(
                UUID.randomUUID(),
                accountId,
                new AccountId("player:" + sinkPlayerId),
                MoneyAmount.ofMinorUnits(amountMinorUnits)));
        bankData.deleteAccount(sinkPlayerId);
    }

    private static TerritoryRestorationRow territoryRestorationByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT restoration.restoration_id,
                               restoration.state,
                               restoration.source_suspended_assessment_id,
                               restoration.next_cycle_prepayment_minor_units,
                               restoration.restoration_fee_minor_units,
                               restoration.total_due_minor_units,
                               assessment.validity
                        FROM territory_maintenance_restoration restoration
                        JOIN territory_fiscal_assessment assessment
                          ON assessment.assessment_id =
                             restoration.source_suspended_assessment_id
                        WHERE restoration.service_identity = ?
                          AND restoration.request_id = ?
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new TerritoryRestorationRow(
                                UUID.fromString(result.getString(1)),
                                result.getString(2),
                                UUID.fromString(result.getString(3)),
                                result.getLong(4),
                                result.getLong(5),
                                result.getLong(6),
                                result.getString(7))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect player Territory Restoration", failure);
        }
    }

    private static long cumulativeNetIssuance(Path databaseFile) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT cumulative_net_issuance_minor_units
                        FROM monetary_supply_summary
                        WHERE singleton = 1
                        """)) {
            try (var result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Monetary Supply summary is missing");
                }
                return result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect cumulative net issuance", failure);
        }
    }

    private static ChunkDimPos paidClaimSeedPosition(ChunkDimPos target) {
        return new ChunkDimPos(
                target.dimension(), new ChunkPos(target.x() + 1, target.z()));
    }

    private static void assertFiscalServiceRegistered(
            GameTestHelper helper,
            Path databaseFile,
            String serviceIdentity,
            String ownerModId,
            String requestId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT s.owner_mod_id, a.administrator_identity, a.request_id, a.reason
                        FROM fiscal_service s
                        JOIN fiscal_service_registration_audit a
                          ON a.service_identity = s.service_identity
                        WHERE s.service_identity = ?
                        """)) {
            query.setString(1, serviceIdentity);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "registered fiscal service row");
                helper.assertValueEqual(ownerModId, result.getString(1), "registered owner Mod ID");
                helper.assertTrue(
                        result.getString(2).startsWith("civic-admin-console:"),
                        "registration administrator identity");
                helper.assertValueEqual(requestId, result.getString(3), "registration request ID");
                helper.assertValueEqual(
                        "Verify audited asynchronous registration",
                        result.getString(4),
                        "registration reason");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect fiscal service command result", failure);
        }
    }

    private static void assertNationFiscalPermissionGranted(
            GameTestHelper helper,
            Path databaseFile,
            UUID ftbTeamId,
            UUID headPlayerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT grant_row.actor_player_id,
                               grant_row.player_id,
                               grant_row.permission,
                               grant_row.reason
                        FROM nation_fiscal_permission_grant grant_row
                        JOIN nation_registry nation
                          ON nation.nation_id = grant_row.nation_id
                        LEFT JOIN nation_fiscal_permission_revocation revocation
                          ON revocation.grant_id = grant_row.grant_id
                        WHERE nation.ftb_team_id = ?
                          AND revocation.grant_id IS NULL
                        """)) {
            query.setString(1, ftbTeamId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Nation Fiscal Permission grant");
                helper.assertValueEqual(
                        headPlayerId.toString(), result.getString(1), "server-derived Nation head actor");
                helper.assertValueEqual(
                        headPlayerId.toString(), result.getString(2), "exact Citizen scope");
                helper.assertValueEqual("APPROVE_BUDGET", result.getString(3), "exact fiscal permission");
                helper.assertValueEqual("GameTest appointment", result.getString(4), "grant audit reason");
                helper.assertFalse(result.next(), "duplicate Nation Fiscal Permission grant");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Nation fiscal role command result", failure);
        }
    }

    private static void assertTerritoryPolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, base_chunks,
                               chunks_per_effective_citizen,
                               effective_at_epoch_millis, reason
                        FROM territory_free_allocation_policy
                        WHERE service_identity = 'civiceconomy-territory-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Territory Free Allocation policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived territory policy administrator");
                helper.assertValueEqual(9, result.getInt(2), "territory base chunks");
                helper.assertValueEqual(4, result.getInt(3), "territory chunks per Effective Citizen");
                helper.assertValueEqual(
                        effectiveAtEpochMillis, result.getLong(4), "territory policy effective time");
                helper.assertValueEqual(
                        "GameTest territory policy", result.getString(5), "territory policy reason");
                helper.assertFalse(result.next(), "duplicate Territory Free Allocation policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect territory policy command result", failure);
        }
    }

    private static void assertTerritoryPricingScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, first_overage_chunk_cost,
                               additional_marginal_cost,
                               effective_at_epoch_millis, reason
                        FROM territory_expansion_pricing_policy
                        WHERE service_identity = 'civiceconomy-territory-pricing'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Territory Expansion pricing policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived territory pricing administrator");
                helper.assertValueEqual(100L, result.getLong(2), "first overage chunk cost");
                helper.assertValueEqual(50L, result.getLong(3), "additional marginal cost");
                helper.assertValueEqual(
                        effectiveAtEpochMillis, result.getLong(4), "territory pricing effective time");
                helper.assertValueEqual(
                        "GameTest territory pricing", result.getString(5), "territory pricing reason");
                helper.assertFalse(result.next(), "duplicate Territory Expansion pricing policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect territory pricing command result", failure);
        }
    }

    private static void assertTerritoryMaintenancePolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, cycle_duration_millis,
                               base_maintenance_per_chargeable_claim_minor_units,
                               enclave_cross_dimension_multiplier_basis_points,
                               force_load_surcharge_minor_units,
                               restoration_fee_minor_units, restoration_cooldown_millis,
                               destruction_basis_points,
                               effective_at_epoch_millis, reason
                        FROM territory_maintenance_policy
                        WHERE service_identity = 'civiceconomy-territory-maintenance-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Territory Maintenance policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived maintenance policy administrator");
                helper.assertValueEqual(604_800_000L, result.getLong(2), "maintenance cycle duration");
                helper.assertValueEqual(75L, result.getLong(3), "base maintenance per claim");
                helper.assertValueEqual(15_000, result.getInt(4), "enclave multiplier");
                helper.assertValueEqual(25L, result.getLong(5), "force-load surcharge");
                helper.assertValueEqual(30L, result.getLong(6), "restoration fee");
                helper.assertValueEqual(1_209_600_000L, result.getLong(7), "restoration cooldown");
                helper.assertValueEqual(6_000, result.getInt(8), "destruction basis points");
                helper.assertValueEqual(
                        effectiveAtEpochMillis, result.getLong(9), "maintenance policy effective time");
                helper.assertValueEqual(
                        "GameTest territory maintenance", result.getString(10), "maintenance policy reason");
                helper.assertFalse(result.next(), "duplicate Territory Maintenance policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect territory maintenance policy command result", failure);
        }
    }

    private static long nextTerritoryMaintenancePolicyBoundary(Path databaseFile) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT MAX(boundary) FROM (
                            SELECT COALESCE(MAX(ends_at_epoch_millis), 0) AS boundary
                            FROM territory_maintenance_cycle
                            UNION ALL
                            SELECT COALESCE(MAX(effective_at_epoch_millis), 0) + 1 AS boundary
                            FROM territory_maintenance_policy
                        )
                        """)) {
            try (var result = query.executeQuery()) {
                return result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read next Territory Maintenance policy boundary", failure);
        }
    }

    private static void assertNationApplicationCreated(
            GameTestHelper helper,
            Path databaseFile,
            UUID ftbTeamId,
            UUID applicantPlayerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT applicant_player_id, state,
                               expires_at_epoch_millis - created_at_epoch_millis
                        FROM nation_application
                        WHERE ftb_team_id = ?
                        ORDER BY created_at_epoch_millis DESC
                        """)) {
            query.setString(1, ftbTeamId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Nation Application row");
                helper.assertValueEqual(
                        applicantPlayerId.toString(), result.getString(1), "application team head");
                helper.assertValueEqual("PENDING", result.getString(2), "application state");
                helper.assertValueEqual(
                        java.time.Duration.ofDays(7).toMillis(),
                        result.getLong(3),
                        "application lifetime");
                helper.assertFalse(result.next(), "duplicate pending Nation Application");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Nation Application command result", failure);
        }
    }

    private static PendingApplicationRow pendingApplication(Path databaseFile, UUID ftbTeamId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT application_id, created_at_epoch_millis
                        FROM nation_application
                        WHERE ftb_team_id = ? AND state = 'PENDING'
                        """)) {
            query.setString(1, ftbTeamId.toString());
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new PendingApplicationRow(
                                UUID.fromString(result.getString(1)), result.getLong(2))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read PENDING Nation Application", failure);
        }
    }

    private static void assertNationApplicationCancelled(
            GameTestHelper helper,
            Path databaseFile,
            UUID applicationId,
            UUID applicantPlayerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT application.state,
                               COUNT(candidate.player_id),
                               SUM(CASE WHEN candidate.ended_at_epoch_millis IS NOT NULL THEN 1 ELSE 0 END),
                               transition.actor_player_id,
                               transition.to_state,
                               transition.reason
                        FROM nation_application application
                        JOIN nation_application_candidate candidate
                          ON candidate.application_id = application.application_id
                        JOIN nation_application_transition transition
                          ON transition.application_id = application.application_id
                        WHERE application.application_id = ?
                        GROUP BY application.state, transition.actor_player_id,
                                 transition.to_state, transition.reason
                        """)) {
            query.setString(1, applicationId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "cancelled Nation Application transition");
                helper.assertValueEqual("CANCELLED", result.getString(1), "application cancellation state");
                helper.assertValueEqual(result.getInt(2), result.getInt(3), "ended Candidate affiliations");
                helper.assertValueEqual(
                        applicantPlayerId.toString(), result.getString(4), "cancellation actor");
                helper.assertValueEqual("CANCELLED", result.getString(5), "cancellation transition");
                helper.assertValueEqual("GameTest cancellation", result.getString(6), "cancellation reason");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect cancelled Nation Application", failure);
        }
    }

    private static void assertNationApplicationAutomaticallyExpired(
            GameTestHelper helper,
            Path databaseFile,
            NationApplicationId applicationId,
            UUID candidatePlayerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT application.state,
                               candidate.ended_at_epoch_millis,
                               transition.service_identity,
                               transition.request_id,
                               transition.actor_player_id,
                               transition.to_state
                        FROM nation_application application
                        JOIN nation_application_candidate candidate
                          ON candidate.application_id = application.application_id
                         AND candidate.player_id = ?
                        JOIN nation_application_transition transition
                          ON transition.application_id = application.application_id
                        WHERE application.application_id = ?
                        """)) {
            query.setString(1, candidatePlayerId.toString());
            query.setString(2, applicationId.value().toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "automatic Nation Application expiry transition");
                helper.assertValueEqual("EXPIRED", result.getString(1), "automatic expiry state");
                helper.assertTrue(result.getObject(2) != null, "ended automatic-expiry Candidate affiliation");
                helper.assertValueEqual(
                        "civiceconomy-nation-application-expiry",
                        result.getString(3),
                        "automatic expiry service");
                helper.assertValueEqual(
                        "automatic-expiry:" + applicationId.value(),
                        result.getString(4),
                        "automatic expiry request identity");
                helper.assertTrue(result.getString(5) == null, "automatic expiry has no player actor");
                helper.assertValueEqual("EXPIRED", result.getString(6), "automatic expiry transition state");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect automatic Nation Application expiry", failure);
        }
    }

    private static void assertCitizenshipCorrectionGraceStarted(
            GameTestHelper helper,
            Path databaseFile,
            UUID ftbTeamId,
            UUID playerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT grace.ftb_team_id, grace.resolution,
                               grace.deadline_epoch_millis - grace.started_at_epoch_millis,
                               citizenship.ended_at_epoch_millis
                        FROM citizenship_correction_grace grace
                        JOIN citizenship_period citizenship
                          ON citizenship.citizenship_id = grace.citizenship_id
                        WHERE grace.player_id = ?
                        ORDER BY grace.started_at_epoch_millis DESC
                        LIMIT 1
                        """)) {
            query.setString(1, playerId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Citizenship Correction Grace");
                helper.assertValueEqual(ftbTeamId.toString(), result.getString(1), "grace FTB Team");
                helper.assertTrue(result.getString(2) == null, "active Citizenship Correction Grace");
                helper.assertValueEqual(
                        java.time.Duration.ofDays(2).toMillis(),
                        result.getLong(3),
                        "Citizenship Correction Grace duration");
                helper.assertTrue(
                        result.getObject(4) == null,
                        "formal Citizenship remains during Correction Grace");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Citizenship Correction Grace", failure);
        }
    }

    private static AccountId activatedTreasury(
            GameTestHelper helper,
            Path databaseFile,
            UUID applicationId,
            UUID playerId,
            ServerPlayer player) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT activation.treasury_account_id, application.state,
                               capital.dimension_id, capital.chunk_x, capital.chunk_z,
                               COUNT(citizenship.citizenship_id)
                        FROM nation_application application
                        JOIN nation_application_activation activation
                          ON activation.application_id = application.application_id
                        JOIN nation_capital capital ON capital.nation_id = activation.nation_id
                        LEFT JOIN citizenship_period citizenship
                          ON citizenship.nation_id = activation.nation_id
                         AND citizenship.player_id = ?
                         AND citizenship.ended_at_epoch_millis IS NULL
                        WHERE application.application_id = ?
                        GROUP BY activation.treasury_account_id, application.state,
                                 capital.dimension_id, capital.chunk_x, capital.chunk_z
                        """)) {
            query.setString(1, playerId.toString());
            query.setString(2, applicationId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "committed Nation activation row");
                helper.assertValueEqual("ACTIVATED", result.getString(2), "application activation state");
                helper.assertValueEqual(
                        player.level().dimension().location().toString(),
                        result.getString(3),
                        "Capital dimension");
                ChunkPos expectedCapital = new ChunkPos(player.blockPosition());
                helper.assertValueEqual(expectedCapital.x, result.getInt(4), "Capital chunk X");
                helper.assertValueEqual(expectedCapital.z, result.getInt(5), "Capital chunk Z");
                helper.assertValueEqual(1, result.getInt(6), "active founder Citizenship");
                return new AccountId(result.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect committed Nation activation", failure);
        }
    }

    private static void claimCapital(GameTestHelper helper, Team team, ServerPlayer player) {
        ChunkPos chunk = new ChunkPos(player.blockPosition());
        ChunkDimPos position = new ChunkDimPos(player.level().dimension(), chunk);
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(helper.getLevel().getServer().createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        var claim = teamData.claim(
                helper.getLevel().getServer().createCommandSourceStack(), position, false);
        if (!claim.isSuccess()) {
            throw new IllegalStateException(
                    "Real FTB Capital claim failed: " + claim.getResultId()
                            + " max=" + teamData.getMaxClaimChunks()
                            + " claimed=" + teamData.getClaimedChunks().size());
        }
    }

    private static void unclaimCapital(GameTestHelper helper, ServerPlayer player) {
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        ClaimedChunk claimed = FTBChunksAPI.api().getManager().getChunk(position);
        if (claimed != null) {
            claimed.unclaim(helper.getLevel().getServer().createCommandSourceStack(), true);
        }
    }

    private static Team createHeadOwnedFtbTeamFixture(ServerPlayer player) {
        try {
            var method = TeamManagerImpl.class.getDeclaredMethod(
                    "createPartyTeamInternal", UUID.class, ServerPlayer.class, String.class);
            method.setAccessible(true);
            Team team = (Team) method.invoke(
                    FTBTeamsAPI.api().getManager(),
                    player.getUUID(),
                    null,
                    "Civic Founding " + UUID.randomUUID());
            ((AbstractTeamBase) team).addMember(player.getUUID(), TeamRank.OWNER);
            team.markDirty();
            return team;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Unable to create exact-version FTB founding GameTest fixture", failure);
        }
    }

    private static NationTeamDirectory snapshot(NationTeam team) {
        return new NationTeamDirectory() {
            @Override
            public java.util.Optional<NationTeam> find(UUID teamId) {
                return team.teamId().equals(teamId)
                        ? java.util.Optional.of(team)
                        : java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return team.citizens().contains(playerId)
                        ? java.util.Optional.of(team)
                        : java.util.Optional.empty();
            }
        };
    }

    private record PendingApplicationRow(UUID applicationId, long createdAtEpochMillis) {}

    private record TerritoryPermitRow(UUID permitId, TerritoryClaimPermitState state) {}

    private record TerritoryRestorationRow(
            UUID restorationId,
            String state,
            UUID sourceAssessmentId,
            long prepayment,
            long fee,
            long total,
            String sourceValidity) {}
}
