package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV81MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v80DatabaseAddsEmptyRollingProductionAssessmentState() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v80.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("4e1ce692-18d5-4c7a-924d-f724f299a91e"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v80 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute(
                    "DROP INDEX production_rolling_marginal_return_contribution_order");
            statement.execute("DROP TABLE production_rolling_marginal_return_contribution");
            statement.execute("DROP TABLE production_rolling_marginal_return_assessment");
            statement.execute("PRAGMA user_version = 80");
        }

        UUID nationId = UUID.fromString("a7acd3c4-0a84-4123-971d-f8ee6c3cc37a");
        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(95, migrated.schemaVersion());
            assertNull(migrated.rollingProductionMarginalReturnAssessment(nationId));
            assertEquals(
                    java.util.List.of(),
                    migrated.rollingProductionContributionAssessments(nationId));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM sqlite_master
                        WHERE type = 'table'
                          AND name IN (
                              'production_rolling_marginal_return_assessment',
                              'production_rolling_marginal_return_contribution'
                          )
                        """)) {
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        }
    }
}
