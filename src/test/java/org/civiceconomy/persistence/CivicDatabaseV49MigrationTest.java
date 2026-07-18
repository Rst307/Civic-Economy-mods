package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV49MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v48DatabaseAddsRecoverableMintBatchesWithoutRewritingRegisteredMint() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v48.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("5bc1f2c8-af72-4e3c-ae45-429a046d97fd"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID nationId = UUID.fromString("ad0b8d80-198d-4df5-af49-c2b0dd0fefee");
        UUID recipeId = UUID.fromString("c06c488e-2c70-4f99-90d1-44084323e5f5");
        UUID mintId = UUID.fromString("2fa7c3aa-278f-4a49-a10b-bb10898a9712");
        long now = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(nationId, "test", "nation", UUID.randomUUID(), now - 1L);
            database.publishMintRecipeVersion(
                    recipeId, "mint", "recipe", 1,
                    List.of(new StoredMintRecipeIngredient(
                            0, "EXACT_ITEM", "minecraft:iron_ingot", 1L, 0L)),
                    1_000L, "Recipe", now);
            database.registerMint(
                    mintId, "mint", "register", nationId, "minecraft:overworld",
                    1, 64, 2, UUID.randomUUID(), UUID.randomUUID(), false,
                    recipeId, UUID.randomUUID(), "Mint", now);
            assertEquals(76, database.schemaVersion());
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE mint_batch_material");
            statement.execute("DROP TABLE mint_batch");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 48");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(76, migrated.schemaVersion());
            assertNotNull(migrated.registeredMint(mintId));
            try (var connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT COUNT(*) AS object_count FROM sqlite_master
                            WHERE (type = 'table' AND name IN (
                                'mint_batch', 'mint_batch_material'
                            )) OR (type = 'index' AND name = 'mint_batch_recovery')
                            """)) {
                assertEquals(3, result.getInt("object_count"));
            }
        }
    }
}
