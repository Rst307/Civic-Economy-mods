package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV82MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v81DatabaseAddsEmptyProductionStrengthPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v81.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("3587f21d-8c7a-4eec-b83f-03438f7fc933"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v81 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX production_strength_policy_current");
            statement.execute("DROP TABLE production_strength_policy");
            statement.execute("PRAGMA user_version = 81");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(95, migrated.schemaVersion());
            assertNull(migrated.currentProductionStrengthPolicy(Long.MAX_VALUE));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM production_strength_policy
                        """)) {
            try (var result = query.executeQuery()) {
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
