package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV86MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v85DatabaseAddsEmptyAuditableEconomicActivityPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v85.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("f8ed8356-3cf0-4c91-a175-d409d14092f4"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish current schema before constructing a genuine v85 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX auditable_economic_activity_policy_current");
            statement.execute("DROP TABLE auditable_economic_activity_policy");
            statement.execute("PRAGMA user_version = 85");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(93, migrated.schemaVersion());
            assertNull(migrated.currentAuditableEconomicActivityPolicy(Long.MAX_VALUE));
        }
    }
}
