package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV90MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v89DatabaseAddsEmptyOnlineTimeObservationPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v89.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("1e0afe1f-08bd-4cd6-bc4b-76c216fbe5ed"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX online_time_observation_policy_current");
            statement.execute("DROP TABLE online_time_observation_policy");
            statement.execute("PRAGMA user_version = 89");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(92, migrated.schemaVersion());
            assertNull(migrated.currentOnlineTimeObservationPolicy(Long.MAX_VALUE));
        }
    }
}
