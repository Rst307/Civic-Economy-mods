package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV96MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v95DatabaseAddsEmptyOnlineDatabaseBackupPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v95.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("b7bfca99-5078-4609-b496-ab3da5a93af2"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX online_database_backup_policy_current");
            statement.execute("DROP TABLE online_database_backup_policy");
            statement.execute("PRAGMA user_version = 95");
        }
        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(96, migrated.schemaVersion());
            assertNull(migrated.currentOnlineDatabaseBackupPolicy(Long.MAX_VALUE));
        }
    }
}
