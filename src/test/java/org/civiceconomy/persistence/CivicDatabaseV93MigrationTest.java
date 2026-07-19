package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV93MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v92DatabaseAddsEmptyNationApplicationLifetimePolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v92.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("5a3ef318-9ac3-40d6-a914-f72fd4619b4f"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX nation_application_lifetime_policy_current");
            statement.execute("DROP TABLE nation_application_lifetime_policy");
            statement.execute("PRAGMA user_version = 92");
        }
        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(97, migrated.schemaVersion());
            assertNull(migrated.currentNationApplicationLifetimePolicy(Long.MAX_VALUE));
        }
    }
}
