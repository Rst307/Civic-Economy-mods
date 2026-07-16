package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.BudgetDraftExpiryProcessor;
import org.civiceconomy.fiscal.BudgetState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV59MigrationTest {
    private static final Instant NOW = Instant.parse("2026-07-16T03:00:00Z");
    private static final UUID BUDGET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path temporaryDirectory;

    @Test
    void v58DatabaseAddsAuditedBudgetDraftExpiry() throws Exception {
        Path file = temporaryDirectory.resolve("v58-budget-draft-expiry.sqlite3");
        DatabaseIdentity identity = identity();
        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            current.createBudget(
                    BUDGET_ID,
                    "public-works",
                    "migration-budget",
                    "nation:migration:treasury",
                    400L,
                    "PUBLIC_WORKS:MIGRATION",
                    "Migration Budget draft",
                    NOW.minusSeconds(1L).toEpochMilli());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE budget_draft_expiry");
            statement.execute("PRAGMA user_version = 58");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            var expired = new BudgetDraftExpiryProcessor(
                            migrated, Clock.fixed(NOW, ZoneOffset.UTC))
                    .expireDue();

            assertEquals(64, migrated.schemaVersion());
            assertEquals(1, expired.size());
            assertEquals(BudgetState.EXPIRED, expired.getFirst().state());
            assertEquals(
                    "automatic-expiry:" + BUDGET_ID,
                    migrated.budgetDraftExpiry(BUDGET_ID).requestId());
        }
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
