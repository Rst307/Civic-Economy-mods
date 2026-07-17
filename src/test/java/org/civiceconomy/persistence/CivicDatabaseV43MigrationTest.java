package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV43MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v42DatabaseAddsIndependentRestorationAggregateWithoutRewritingHistory()
            throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v42.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("4c514b3a-e3f1-4c17-96dc-749cf1eeb41f"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(72, ignored.schemaVersion());
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE territory_maintenance_restoration");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 42");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(72, migrated.schemaVersion());
            try (var connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT COUNT(*) AS table_count
                            FROM sqlite_master
                            WHERE type = 'table'
                              AND name = 'territory_maintenance_restoration'
                            """)) {
                assertEquals(1, result.getInt("table_count"));
            }
        }
    }
}
