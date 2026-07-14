package org.civiceconomy.platform.neoforge;

import com.mojang.authlib.GameProfile;
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
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyFiscalAccounts;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.nation.CreateNationApplication;
import org.civiceconomy.nation.NationApplicationId;
import org.civiceconomy.nation.NationApplicationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.JoinCitizenship;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.RegisterNation;

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
                Set.of("apply", "status", "cancel", "activate", "population", "role"),
                economy.getChild("nation").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Nation Application command actions");
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
}
