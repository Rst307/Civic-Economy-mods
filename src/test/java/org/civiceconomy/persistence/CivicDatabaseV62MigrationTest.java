package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV62MigrationTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void v61ApprovedBudgetsReceiveExplicitLegacyAuditEvidence() throws Exception {
        Path file = temporaryDirectory.resolve("v61-budget-approval.sqlite3");
        DatabaseIdentity identity = identity();
        UUID budgetId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            current.createBudget(
                    budgetId,
                    "civiceconomy-budget",
                    "legacy-budget",
                    "nation:22222222-2222-2222-2222-222222222222:treasury",
                    300L,
                    "PUBLIC_WORKS",
                    "Legacy approved Budget",
                    NOW.plusSeconds(3_600L).toEpochMilli());
            current.approveBudget(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "civiceconomy-budget",
                    "legacy-approval",
                    budgetId,
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    "Original audit removed to simulate v61",
                    NOW.toEpochMilli());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE budget_approval_audit");
            statement.execute("PRAGMA user_version = 61");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            StoredBudgetApprovalAudit audit = migrated.budgetApprovalAudit(budgetId);

            assertEquals(69, migrated.schemaVersion());
            assertEquals(
                    UUID.fromString("00000000-0000-0000-0000-000000000000"),
                    audit.actorPlayerId());
            assertEquals(
                    "Legacy Budget approval migrated without player audit",
                    audit.reason());
            assertEquals(0L, audit.approvedAtEpochMillis());
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
