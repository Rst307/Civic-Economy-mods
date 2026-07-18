package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV46MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v45DatabaseAddsRestartOnlyRestoreOperationsAndImmutableAudit() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v45.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("108de65a-ea81-4010-b358-29653d623bca"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(80, ignored.schemaVersion());
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE database_restore_audit");
            statement.execute("DROP TABLE database_restore_operation");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 45");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(80, migrated.schemaVersion());
            try (var connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT COUNT(*) AS object_count
                            FROM sqlite_master
                            WHERE (type = 'table'
                                      AND name IN (
                                          'database_restore_operation',
                                          'database_restore_audit'
                                      ))
                               OR (type = 'index'
                                      AND name = 'database_restore_one_staged')
                            """)) {
                assertEquals(3, result.getInt("object_count"));
            }
        }
    }
}
