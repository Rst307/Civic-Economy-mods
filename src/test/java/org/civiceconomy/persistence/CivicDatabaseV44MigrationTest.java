package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV44MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v43DatabaseAddsExactRestorationCreditApplicationAudit() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v43.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("6769f9e5-a176-41fd-b7cc-f86476d0db58"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(48, ignored.schemaVersion());
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute(
                    "DROP TABLE territory_maintenance_restoration_credit_application");
            statement.execute("PRAGMA user_version = 43");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(48, migrated.schemaVersion());
            try (var connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT COUNT(*) AS table_count
                            FROM sqlite_master
                            WHERE type = 'table'
                              AND name =
                                  'territory_maintenance_restoration_credit_application'
                            """)) {
                assertEquals(1, result.getInt("table_count"));
            }
        }
    }
}
