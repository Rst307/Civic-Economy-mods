package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV76MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v75DatabaseAddsPersistentProductionExportAudit() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v75.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("9ca20d1e-563a-4d2c-8c2b-e89ba895f39f"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v75 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX facility_production_inventory_export_interface_time");
            statement.execute("DROP TABLE facility_production_inventory_export");
            statement.execute("DROP INDEX facility_production_inventory_export_handoff_destination");
            statement.execute("DROP TABLE facility_production_inventory_export_handoff");
            statement.execute("PRAGMA user_version = 75");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(82, migrated.schemaVersion());
            assertNull(migrated.productionInventoryExport(
                    "civiceconomy-production-export", "missing"));
            assertNull(migrated.productionInventoryExportHandoff(
                    "civiceconomy-production-handoff", "missing"));
        }
    }
}
