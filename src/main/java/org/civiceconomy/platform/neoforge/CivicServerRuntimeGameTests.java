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
                Set.of("allowance", "prepare", "cancel"),
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
        long effectiveAt = java.time.Instant.now().plusSeconds(60L).toEpochMilli();
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin territory policy schedule 9 4 " + effectiveAt
                        + " " + requestId + " GameTest territory policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin territory pricing schedule 100 50 " + effectiveAt
                        + " " + pricingRequestId + " GameTest territory pricing");
        Path databaseFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        helper.succeedWhen(() -> {
            assertTerritoryPolicyScheduled(helper, databaseFile, requestId, effectiveAt);
            assertTerritoryPricingScheduled(
                    helper, databaseFile, pricingRequestId, effectiveAt);
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

    @GameTest(template = "empty", timeoutTicks = 500)
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
                clearFiscalAccount(
                        helper,
                        org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                .CLEARING_ACCOUNT_ID);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 500)
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
        AccountId treasury = new AccountId(
                "nation:" + nationId.value() + ":treasury");
        var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, displayName);
        UUID fundingPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        var funding = bankData.getAccount(fundingPlayerId);
        funding.getMoneyStorage().clear();
        if (!BankAPI.getApi().BankDepositFromServer(
                funding, CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1_000L))) {
            throw new IllegalStateException("Unable to seed paid claim LC funding account");
        }
        LightmansCurrencyPayments.live(helper.getLevel()).apply(new ExternalPayment(
                UUID.randomUUID(),
                new AccountId("player:" + fundingPlayerId),
                treasury,
                MoneyAmount.ofMinorUnits(1_000L)));
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
}
