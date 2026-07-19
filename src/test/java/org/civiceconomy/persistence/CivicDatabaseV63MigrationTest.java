package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV63MigrationTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void v62ReleasedBudgetsReceiveExplicitLegacyCancellationAuditEvidence() throws Exception {
        Path file = temporaryDirectory.resolve("v62-budget-cancellation.sqlite3");
        DatabaseIdentity identity = identity();
        UUID budgetId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            StoredBudget approved = current.approveBudget(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "civiceconomy-budget",
                    "legacy-approval",
                    current.createBudget(
                                    budgetId,
                                    "civiceconomy-budget",
                                    "legacy-budget",
                                    "nation:22222222-2222-2222-2222-222222222222:treasury",
                                    300L,
                                    "PUBLIC_WORKS",
                                    "Legacy released Budget",
                                    NOW.plusSeconds(3_600L).toEpochMilli())
                            .budgetId(),
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    "Prepare legacy released Budget",
                    NOW.minusSeconds(60L).toEpochMilli());
            StoredEscrow escrow = current.escrow(approved.escrowId());
            current.releaseReservation(
                    UUID.randomUUID(),
                    "legacy-budget-service",
                    "legacy-budget-release",
                    escrow.reservationId(),
                    "Original audit removed to simulate v62",
                    NOW.toEpochMilli());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE budget_cancellation_audit");
            statement.execute("PRAGMA user_version = 62");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            StoredBudgetCancellationAudit audit = migrated.budgetCancellationAudit(budgetId);

            assertEquals(88, migrated.schemaVersion());
            assertEquals("legacy-budget-service", audit.serviceIdentity());
            assertEquals("legacy-budget-release", audit.requestId());
            assertEquals(
                    UUID.fromString("00000000-0000-0000-0000-000000000000"),
                    audit.actorPlayerId());
            assertEquals(
                    "Legacy Budget cancellation migrated without player audit",
                    audit.reason());
            assertEquals(NOW.toEpochMilli(), audit.cancelledAtEpochMillis());
        }
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
