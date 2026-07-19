package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV89MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v88PolicyHistoryIsPreservedButDoesNotInventAReconciliationInterval()
            throws Exception {
        Path file = temporaryDirectory.resolve("schema-v88.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("ddf7bbbe-d5f5-4658-b6fc-8ad245a16092"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(file, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX citizenship_policy_current");
            statement.execute("ALTER TABLE citizenship_policy RENAME TO citizenship_policy_v89");
            statement.execute("""
                    CREATE TABLE citizenship_policy (
                        policy_id TEXT PRIMARY KEY,
                        service_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        actor_identity TEXT NOT NULL,
                        correction_grace_millis INTEGER NOT NULL,
                        transfer_cooldown_millis INTEGER NOT NULL,
                        effective_at_epoch_millis INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        recorded_at_epoch_millis INTEGER NOT NULL,
                        UNIQUE (service_identity, request_id)
                    )
                    """);
            statement.execute("""
                    INSERT INTO citizenship_policy VALUES (
                        '8943b586-85ec-4e9e-8239-40abdbf31d69',
                        'legacy-policy-service', 'legacy-policy-request', 'legacy-admin',
                        172800000, 604800000, 1, 'Legacy policy', 1
                    )
                    """);
            statement.execute("DROP TABLE citizenship_policy_v89");
            statement.execute("""
                    CREATE INDEX citizenship_policy_current
                    ON citizenship_policy (
                        effective_at_epoch_millis,
                        recorded_at_epoch_millis,
                        policy_id
                    )
                    """);
            statement.execute("PRAGMA user_version = 88");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(90, migrated.schemaVersion());
            assertNull(migrated.currentCitizenshipPolicy(Long.MAX_VALUE));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT reconciliation_interval_millis
                        FROM citizenship_policy
                        WHERE request_id = 'legacy-policy-request'
                        """)) {
            assertEquals(1, result.next() ? 1 : 0);
            assertNull(result.getObject("reconciliation_interval_millis"));
        }
    }
}
