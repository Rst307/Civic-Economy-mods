package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalLedgerBudgetTest {
    private static final Instant NOW = Instant.parse("2026-07-14T04:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void budgetDraftSurvivesRestartWithoutReservingFunds() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        CreateBudget request = new CreateBudget(
                new ServiceIdentity("aurora-fiscal-office"),
                "draft-bridge-budget",
                treasury,
                MoneyAmount.ofMinorUnits(400),
                "PUBLIC_WORKS:BRIDGE_17",
                "Bridge construction budget",
                NOW.plusSeconds(604_800));
        Budget created;

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, treasury);

            created = ledger.createBudget(request);

            assertEquals(BudgetState.DRAFT, created.state());
            assertEquals(Optional.empty(), created.escrowId());
            assertEquals(MoneyAmount.ZERO, created.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(400), created.remainingAmount());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
            assertEquals(MoneyAmount.ofMinorUnits(1_000), ledger.availableBalance(treasury));
        }

        try (CivicDatabase reopened = database()) {
            FiscalLedger ledger = ledger(reopened, treasury);
            assertEquals(created, ledger.createBudget(request));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void budgetApprovalAtomicallyCreatesItsEscrowAndReplaysAcrossRestart() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        CreateBudget create = new CreateBudget(
                new ServiceIdentity("aurora-fiscal-office"),
                "draft-road-budget",
                treasury,
                MoneyAmount.ofMinorUnits(400),
                "PUBLIC_WORKS:ROAD_9",
                "Road construction budget",
                NOW.plusSeconds(604_800));
        ApproveBudget approval;
        Budget approved;

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, treasury);
            Budget draft = ledger.createBudget(create);
            approval = new ApproveBudget(
                    new ServiceIdentity("aurora-budget-approver"),
                    "approve-road-budget",
                    draft.budgetId(),
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    "Approve road Budget");

            approved = ledger.approveBudget(approval);

            assertEquals(BudgetState.APPROVED, approved.state());
            assertEquals(true, approved.escrowId().isPresent());
            assertEquals(MoneyAmount.ZERO, approved.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(400), approved.remainingAmount());
            assertEquals(MoneyAmount.ofMinorUnits(400), ledger.reservedBalance(treasury));
            assertEquals(MoneyAmount.ofMinorUnits(600), ledger.availableBalance(treasury));
        }

        try (CivicDatabase reopened = database()) {
            FiscalLedger ledger = ledger(reopened, treasury);
            assertEquals(approved, ledger.approveBudget(approval));
            assertEquals(MoneyAmount.ofMinorUnits(400), ledger.reservedBalance(treasury));
            assertEquals(MoneyAmount.ofMinorUnits(600), ledger.availableBalance(treasury));
        }
    }

    @Test
    void approvedBudgetTracksPartialAndFinalSpending() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        CreateBudget create = new CreateBudget(
                new ServiceIdentity("aurora-fiscal-office"),
                "draft-harbor-budget",
                treasury,
                MoneyAmount.ofMinorUnits(400),
                "PUBLIC_WORKS:HARBOR_3",
                "Harbor construction budget",
                NOW.plusSeconds(604_800));

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, treasury);
            Budget draft = ledger.createBudget(create);
            Budget approved = ledger.approveBudget(new ApproveBudget(
                    new ServiceIdentity("aurora-budget-approver"),
                    "approve-harbor-budget",
                    draft.budgetId(),
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    "Approve harbor Budget"));
            Escrow escrow = ledger.escrow(approved.escrowId().orElseThrow());
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});

            coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("aurora-fiscal-office"),
                            "harbor-first-milestone",
                            escrow.reservationId(),
                            new AccountId("organization:harbor-builder:fiscal"),
                            MoneyAmount.ofMinorUnits(150)),
                    FailurePoint.NONE);

            Budget partial = ledger.createBudget(create);
            assertEquals(BudgetState.PARTIALLY_SPENT, partial.state());
            assertEquals(MoneyAmount.ofMinorUnits(150), partial.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(250), partial.remainingAmount());

            coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("aurora-fiscal-office"),
                            "harbor-final-milestone",
                            escrow.reservationId(),
                            new AccountId("organization:harbor-builder:fiscal"),
                            MoneyAmount.ofMinorUnits(250)),
                    FailurePoint.NONE);

            Budget spent = ledger.createBudget(create);
            assertEquals(BudgetState.SPENT, spent.state());
            assertEquals(MoneyAmount.ofMinorUnits(400), spent.settledAmount());
            assertEquals(MoneyAmount.ZERO, spent.remainingAmount());
        }
    }

    @Test
    void releasingAnApprovedBudgetPreservesSpentAmountAndFreesTheRemainder() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        CreateBudget create = new CreateBudget(
                new ServiceIdentity("aurora-fiscal-office"),
                "draft-canal-budget",
                treasury,
                MoneyAmount.ofMinorUnits(400),
                "PUBLIC_WORKS:CANAL_5",
                "Canal construction budget",
                NOW.plusSeconds(604_800));

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, treasury);
            Budget draft = ledger.createBudget(create);
            Budget approved = ledger.approveBudget(new ApproveBudget(
                    new ServiceIdentity("aurora-budget-approver"),
                    "approve-canal-budget",
                    draft.budgetId(),
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    "Approve canal Budget"));
            Escrow escrow = ledger.escrow(approved.escrowId().orElseThrow());
            new PaymentCoordinator(database, ignored -> {}).settle(
                    new SettleReservation(
                            new ServiceIdentity("aurora-fiscal-office"),
                            "canal-completed-work",
                            escrow.reservationId(),
                            new AccountId("organization:canal-builder:fiscal"),
                            MoneyAmount.ofMinorUnits(150)),
                    FailurePoint.NONE);

            ledger.release(new ReleaseReservation(
                    new ServiceIdentity("aurora-fiscal-office"),
                    "release-canal-budget",
                    escrow.reservationId(),
                    "Remaining canal work cancelled"));

            Budget released = ledger.createBudget(create);
            assertEquals(BudgetState.RELEASED, released.state());
            assertEquals(MoneyAmount.ofMinorUnits(150), released.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(250), released.remainingAmount());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void approvedBudgetExpiresWithItsEscrowAtTheDeadline() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        Instant deadline = NOW.plusSeconds(60);
        CreateBudget create = new CreateBudget(
                new ServiceIdentity("aurora-fiscal-office"),
                "draft-library-budget",
                treasury,
                MoneyAmount.ofMinorUnits(400),
                "PUBLIC_WORKS:LIBRARY_2",
                "Library construction budget",
                deadline);

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, treasury);
            Budget draft = ledger.createBudget(create);
            Budget approved = ledger.approveBudget(new ApproveBudget(
                    new ServiceIdentity("aurora-budget-approver"),
                    "approve-library-budget",
                    draft.budgetId(),
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    "Approve library Budget"));

            FiscalLedger atDeadline = ledger(database, treasury, deadline);
            atDeadline.expireEscrow(new ExpireEscrow(
                    new ServiceIdentity("civiceconomy-server"),
                    "expire-library-budget",
                    approved.escrowId().orElseThrow()));

            Budget expired = atDeadline.createBudget(create);
            assertEquals(BudgetState.EXPIRED, expired.state());
            assertEquals(MoneyAmount.ZERO, expired.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(400), expired.remainingAmount());
            assertEquals(MoneyAmount.ZERO, atDeadline.reservedBalance(treasury));
        }
    }

    private FiscalLedger ledger(CivicDatabase database, AccountId treasury) {
        return ledger(database, treasury, NOW);
    }

    private FiscalLedger ledger(CivicDatabase database, AccountId treasury, Instant now) {
        return new FiscalLedger(
                database,
                account -> account.equals(treasury)
                        ? MoneyAmount.ofMinorUnits(1_000)
                        : MoneyAmount.ZERO,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("budget.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("4b617458-7f03-4fd2-a94e-4dc37ecbd682"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
