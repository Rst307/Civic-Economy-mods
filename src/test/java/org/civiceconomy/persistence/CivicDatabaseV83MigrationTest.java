package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV83MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v82DatabaseAddsEmptyEffectiveCitizenStrengthPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v82.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("644f4a71-4d57-4140-98e7-ef28b715c9a0"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v82 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX effective_citizen_strength_policy_current");
            statement.execute("DROP TABLE effective_citizen_strength_policy");
            statement.execute("PRAGMA user_version = 82");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(97, migrated.schemaVersion());
            assertNull(migrated.currentEffectiveCitizenStrengthPolicy(Long.MAX_VALUE));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM effective_citizen_strength_policy
                        """)) {
            try (var result = query.executeQuery()) {
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
