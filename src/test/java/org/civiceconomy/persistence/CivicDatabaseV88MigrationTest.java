package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV88MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v87DatabaseAddsEmptyCitizenshipPolicyHistory() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v87.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("ee548fc9-50fe-4547-b8dc-b7a2b0920d71"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX citizenship_policy_current");
            statement.execute("DROP TABLE citizenship_policy");
            statement.execute("PRAGMA user_version = 87");
        }
        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(93, migrated.schemaVersion());
            assertNull(migrated.currentCitizenshipPolicy(Long.MAX_VALUE));
        }
    }
}
