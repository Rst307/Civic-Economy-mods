package org.civiceconomy.platform.neoforge;

import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
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
}
