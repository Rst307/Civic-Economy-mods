package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV85MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v84DatabaseAddsEmptyMintCompliancePolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v84.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("982bff0e-42cc-4a4a-b90d-39ece3853f03"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v84 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX mint_compliance_policy_current");
            statement.execute("DROP TABLE mint_compliance_policy");
            statement.execute("PRAGMA user_version = 84");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(96, migrated.schemaVersion());
            assertNull(migrated.currentMintCompliancePolicy(Long.MAX_VALUE));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM mint_compliance_policy
                        """)) {
            try (var result = query.executeQuery()) {
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
