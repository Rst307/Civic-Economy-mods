package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV91MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v90DatabaseAddsEmptyNationApplicationExpiryPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v90.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("1fb741eb-0032-455c-bb5c-25dfedfb913a"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX nation_application_expiry_policy_current");
            statement.execute("DROP TABLE nation_application_expiry_policy");
            statement.execute("PRAGMA user_version = 90");
        }
        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(91, migrated.schemaVersion());
            assertNull(migrated.currentNationApplicationExpiryPolicy(Long.MAX_VALUE));
        }
    }
}
