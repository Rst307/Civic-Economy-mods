package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV66MigrationTest {
    private static final String SERVICE = "civiceconomy-permanent-destruction";
    private static final String TREASURY =
            "nation:11111111-1111-1111-1111-111111111111:treasury";

    @TempDir Path temporaryDirectory;

    @Test
    void v65PendingAndCommittedDestructionsMigrateWithoutRepeatingIssuanceChanges()
            throws Exception {
        Path file = temporaryDirectory.resolve("schema-v65.sqlite3");
        DatabaseIdentity identity = identity();
        UUID pendingId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID committedId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            current.confirmMonetarySupplyChange(
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    SERVICE,
                    "seed-v66-migration",
                    "ISSUANCE",
                    1_000L,
                    "mint:v66-migration",
                    "Seed v66 migration",
                    1_000L,
                    1_000L);
            current.preparePermanentDestruction(
                    pendingId,
                    SERVICE,
                    "pending-v66-migration",
                    TREASURY,
                    100L,
                    "Pending v65 destruction",
                    2_000L);
            current.preparePermanentDestruction(
                    committedId,
                    SERVICE,
                    "committed-v66-migration",
                    TREASURY,
                    200L,
                    "Committed v65 destruction",
                    3_000L);
            current.markPermanentDestructionExternalApplied(committedId, 4_000L);
            current.commitPermanentDestruction(committedId, 5_000L);
            assertEquals(800L, current.cumulativeNetIssuanceMinorUnits());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE permanent_destruction_operation "
                            + "DROP COLUMN external_applied_at_epoch_millis");
            statement.execute("PRAGMA user_version = 65");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(75, migrated.schemaVersion());

            StoredPermanentDestructionOperation pending =
                    migrated.permanentDestructionOperation(
                            SERVICE, "pending-v66-migration");
            assertEquals("PREPARED", pending.state());
            assertNull(pending.externalAppliedAtEpochMillis());

            StoredPermanentDestructionOperation committed =
                    migrated.permanentDestructionOperation(
                            SERVICE, "committed-v66-migration");
            assertEquals("COMMITTED", committed.state());
            assertNull(committed.externalAppliedAtEpochMillis());
            assertEquals(
                    "PERMANENT_DESTRUCTION",
                    migrated.commitPermanentDestruction(committedId, 6_000L).changeKind());
            assertEquals(800L, migrated.cumulativeNetIssuanceMinorUnits());

            migrated.markPermanentDestructionExternalApplied(pendingId, 7_000L);
            migrated.commitPermanentDestruction(pendingId, 8_000L);
            assertEquals(700L, migrated.cumulativeNetIssuanceMinorUnits());
        }
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
