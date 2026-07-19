package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV92MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v91DatabaseAddsEmptyCandidateOnlineEvidencePolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v91.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("1fb741eb-0032-455c-bb5c-25dfedfb913a"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX candidate_online_evidence_policy_current");
            statement.execute("DROP TABLE candidate_online_evidence_policy");
            statement.execute("PRAGMA user_version = 91");
        }
        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(96, migrated.schemaVersion());
            assertNull(migrated.currentCandidateOnlineEvidencePolicy(Long.MAX_VALUE));
        }
    }
}
