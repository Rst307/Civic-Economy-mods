package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV45MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v44DatabaseAddsDurableBackupOperationsAndImmutableAudit() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v44.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("cad97534-b19d-4e6c-8874-19e201cb6281"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(95, ignored.schemaVersion());
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE database_backup_audit");
            statement.execute("DROP TABLE database_backup_operation");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 44");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(95, migrated.schemaVersion());
            try (var connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT COUNT(*) AS table_count
                            FROM sqlite_master
                            WHERE type = 'table'
                              AND name IN (
                                  'database_backup_operation',
                                  'database_backup_audit'
                              )
                            """)) {
                assertEquals(2, result.getInt("table_count"));
            }
        }
    }
}
