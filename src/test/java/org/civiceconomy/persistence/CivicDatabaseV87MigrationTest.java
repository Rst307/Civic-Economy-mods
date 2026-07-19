package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV87MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v86DatabaseAddsEmptyRegisteredFacilityScopePolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v86.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("6afdd55e-e504-4f70-971a-8988af6ff836"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX registered_facility_scope_policy_current");
            statement.execute("DROP TABLE registered_facility_scope_policy");
            statement.execute("PRAGMA user_version = 86");
        }
        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(87, migrated.schemaVersion());
            assertNull(migrated.currentRegisteredFacilityScopePolicy(Long.MAX_VALUE));
        }
    }
}
