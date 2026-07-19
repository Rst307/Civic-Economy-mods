package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV74MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v73DatabaseAddsEmptyGlobalReferencePriceHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v73.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("8ca20d1e-563a-4d2c-8c2b-e89ba895f39f"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v73 fixture.
        }
        downgradeToV73(file);

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(86, migrated.schemaVersion());
            assertNull(migrated.currentGlobalReferencePrice(
                    "minecraft:iron_ingot", "components:{}", Long.MAX_VALUE));

            StoredGlobalReferencePrice scheduled = migrated.scheduleGlobalReferencePrice(
                    UUID.fromString("341ce427-59e7-49a7-8c5a-47f8d0ff63d9"),
                    "migration",
                    "initial-iron-price",
                    "civic-admin-console:migration",
                    "minecraft:iron_ingot",
                    "components:{}",
                    25L,
                    100L,
                    "Prove migrated reference-price history is writable",
                    90L);

            assertNotNull(scheduled);
            assertEquals(25L, migrated.currentGlobalReferencePrice(
                    "minecraft:iron_ingot", "components:{}", 100L)
                    .unitPriceMinorUnits());
        }
    }

    private static void downgradeToV73(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX global_reference_price_current");
            statement.execute("DROP TABLE global_reference_price");
            statement.execute("PRAGMA user_version = 73");
        }
    }
}
