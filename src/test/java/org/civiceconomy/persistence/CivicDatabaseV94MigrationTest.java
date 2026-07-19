package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV94MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v93DatabaseAddsEmptyFormalFoundingCandidateThresholdHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v93.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("017f0339-4a46-48e3-a435-3e87dc83de85"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX nation_founding_candidate_threshold_policy_current");
            statement.execute("DROP TABLE nation_founding_candidate_threshold_policy");
            statement.execute("PRAGMA user_version = 93");
        }
        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(95, migrated.schemaVersion());
            assertNull(migrated.currentNationFoundingCandidateThresholdPolicy(Long.MAX_VALUE));
        }
    }
}
