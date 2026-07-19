package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV75MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v74DatabaseAddsPersistentInventoryAgeTables() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v74.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("8ca20d1e-563a-4d2c-8c2b-e89ba895f39f"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v74 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE facility_production_inventory_consumption_allocation");
            statement.execute("DROP TABLE facility_production_inventory_consumption");
            statement.execute("DROP INDEX facility_production_inventory_age_lookup");
            statement.execute("DROP TABLE facility_production_inventory_age");
            statement.execute("PRAGMA user_version = 74");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(85, migrated.schemaVersion());
            assertNotNull(migrated.productionInventoryAgeBatches(
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")));
        }
    }
}
