package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV95MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v94DatabaseAddsEmptyEffectiveCitizenPopulationPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v94.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("6ff7e646-ebf2-47ab-98de-193c30e9c33b"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX effective_citizen_population_policy_current");
            statement.execute("DROP TABLE effective_citizen_population_policy");
            statement.execute("PRAGMA user_version = 94");
        }
        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(96, migrated.schemaVersion());
            assertNull(migrated.currentEffectiveCitizenPopulationPolicy(Long.MAX_VALUE));
        }
    }
}
