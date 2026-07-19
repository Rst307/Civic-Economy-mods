package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetDraftExpiryProcessorTest {
    private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");
    private static final AccountId TREASURY =
            new AccountId("nation:budget-expiry:treasury");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("public-works");

    @TempDir
    Path temporaryDirectory;

    @Test
    void classifiesOnlyDueDraftsWithoutCreatingFiscalHoldsAndReplaysExactly() {
        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, NOW);
            Budget due = ledger.createBudget(new CreateBudget(
                    SERVICE,
                    "due-budget",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(300L),
                    "PUBLIC_WORKS:DUE",
                    "Due draft budget",
                    NOW.plusSeconds(60L)));
            Budget future = ledger.createBudget(new CreateBudget(
                    SERVICE,
                    "future-budget",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(200L),
                    "PUBLIC_WORKS:FUTURE",
                    "Future draft budget",
                    NOW.plusSeconds(120L)));
            BudgetDraftExpiryProcessor processor = new BudgetDraftExpiryProcessor(
                    database,
                    Clock.fixed(NOW.plusSeconds(90L), ZoneOffset.UTC));

            assertEquals(1, processor.expireDue().size());
            assertEquals(BudgetState.EXPIRED, ledger.createBudget(request(due)).state());
            assertEquals(BudgetState.DRAFT, ledger.createBudget(request(future)).state());
            assertEquals(Optional.empty(), ledger.createBudget(request(due)).escrowId());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(TREASURY));
            assertThrows(
                    IllegalStateException.class,
                    () -> ledger.approveBudget(new ApproveBudget(
                            new ServiceIdentity("budget-approver"),
                            "approve-expired-budget",
                            due.budgetId(),
                            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                            "Expired Budget must not approve")));
            assertEquals(
                    "automatic-expiry:" + due.budgetId(),
                    database.budgetDraftExpiry(due.budgetId()).requestId());

            assertEquals(0, processor.expireDue().size());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(TREASURY));
        }
    }

    @Test
    void changedDraftDoesNotBlockLaterDueDrafts() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("changed-budget-draft.sqlite3");
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID changedId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID laterId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity())) {
            createDueDraft(database, firstId, "first-draft", 3L);
            createDueDraft(database, changedId, "changed-draft", 2L);
            createDueDraft(database, laterId, "later-draft", 1L);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                    var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER change_next_budget_after_first_expiry
                        AFTER INSERT ON budget_draft_expiry
                        WHEN NEW.budget_id = '00000000-0000-0000-0000-000000000001'
                        BEGIN
                            UPDATE fiscal_budget
                            SET state = 'EXPIRED'
                            WHERE budget_id = '00000000-0000-0000-0000-000000000002';
                        END
                        """);
            }
            BudgetDraftExpiryProcessor processor = new BudgetDraftExpiryProcessor(
                    database, Clock.fixed(NOW, ZoneOffset.UTC));

            assertEquals(2, processor.expireDue().size());
            assertEquals("EXPIRED", database.budget(firstId).state());
            assertEquals("EXPIRED", database.budget(changedId).state());
            assertEquals("EXPIRED", database.budget(laterId).state());
            assertNull(database.budgetDraftExpiry(changedId));
            assertEquals(
                    "automatic-expiry:" + laterId,
                    database.budgetDraftExpiry(laterId).requestId());
        }
    }

    private static void createDueDraft(
            CivicDatabase database, UUID budgetId, String requestId, long expiryOffsetMillis) {
        database.createBudget(
                budgetId,
                SERVICE.value(),
                requestId,
                TREASURY.value(),
                100L,
                "PUBLIC_WORKS:EXPIRY_ISOLATION",
                "Budget expiry isolation",
                NOW.minusMillis(expiryOffsetMillis).toEpochMilli());
    }

    private static CreateBudget request(Budget budget) {
        return new CreateBudget(
                budget.serviceIdentity(),
                budget.requestId(),
                budget.sourceAccount(),
                budget.amount(),
                budget.budgetCode(),
                budget.purpose(),
                budget.expiresAt());
    }

    private FiscalLedger ledger(CivicDatabase database, Instant now) {
        return new FiscalLedger(
                database,
                ignored -> MoneyAmount.ofMinorUnits(1_000L),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("budget-draft-expiry.sqlite3"), identity());
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("23e57ead-5948-4344-8f33-c81ac77a15d7"),
                "0.1.0",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
