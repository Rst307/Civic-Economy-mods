package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV78MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v77DatabaseAddsEmptyProductionMarginalReturnPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v77.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("1d679ea7-347f-44bb-82fd-218e1c8743d5"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {
            // Establish the current schema before constructing a genuine v77 fixture.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX production_marginal_return_policy_current");
            statement.execute("DROP TABLE production_marginal_return_policy");
            statement.execute("PRAGMA user_version = 77");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(92, migrated.schemaVersion());
            assertNull(migrated.currentProductionMarginalReturnPolicy(Long.MAX_VALUE));

            StoredProductionMarginalReturnPolicy scheduled =
                    migrated.scheduleProductionMarginalReturnPolicy(
                            UUID.fromString("d4b58693-1e31-4077-a008-e83775d14b15"),
                            "migration",
                            "initial-production-marginal-return-policy",
                            "civic-admin-console:migration",
                            100_000L,
                            5_000,
                            500_000L,
                            2_500,
                            100L,
                            "Prove migrated policy history is writable",
                            90L);

            assertNotNull(scheduled);
            assertEquals(
                    100_000L,
                    migrated.currentProductionMarginalReturnPolicy(100L)
                            .facilitySoftCapMinorUnits());
        }
    }
}
