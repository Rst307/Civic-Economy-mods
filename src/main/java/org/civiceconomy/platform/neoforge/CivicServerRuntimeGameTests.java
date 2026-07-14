package org.civiceconomy.platform.neoforge;

import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.civiceconomy.CivicEconomy;

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

    private static Team createHeadOwnedFtbTeamFixture(ServerPlayer player) {
        try {
            var method = TeamManagerImpl.class.getDeclaredMethod(
                    "createPartyTeamInternal", UUID.class, ServerPlayer.class, String.class);
            method.setAccessible(true);
            return (Team) method.invoke(
                    FTBTeamsAPI.api().getManager(),
                    player.getUUID(),
                    null,
                    "Civic Founding " + UUID.randomUUID());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Unable to create exact-version FTB founding GameTest fixture", failure);
        }
    }
}
