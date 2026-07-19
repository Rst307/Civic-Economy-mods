package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV84MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v83DatabaseAddsEmptyEffectiveTerritoryStrengthPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v83.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("e50cb22b-1da1-43ac-8b36-18f04610dff1"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v83 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX effective_territory_strength_policy_current");
            statement.execute("DROP TABLE effective_territory_strength_policy");
            statement.execute("PRAGMA user_version = 83");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(87, migrated.schemaVersion());
            assertNull(migrated.currentEffectiveTerritoryStrengthPolicy(Long.MAX_VALUE));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM effective_territory_strength_policy
                        """)) {
            try (var result = query.executeQuery()) {
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
