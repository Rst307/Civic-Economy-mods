package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV80MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v79DatabaseAddsEmptyVersionBoundProductionContributionHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v79.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("1d679ea7-347f-44bb-82fd-218e1c8743d5"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v79 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX production_marginal_return_contribution_window");
            statement.execute("DROP TABLE production_marginal_return_contribution");
            statement.execute("PRAGMA user_version = 79");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(85, migrated.schemaVersion());
            assertNull(migrated.productionMarginalReturnContribution(
                    UUID.fromString("d4b58693-1e31-4077-a008-e83775d14b15")));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var query = connection.prepareStatement("""
                        SELECT name FROM sqlite_master
                        WHERE type = 'table'
                          AND name = 'production_marginal_return_contribution'
                        """)) {
            assertTrue(query.executeQuery().next());
        }
    }
}
