package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetDisbursementRecoveryInspectionTest {
    private static final Instant NOW = Instant.parse("2026-07-17T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE =
            NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY;
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID ACTOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AccountId TREASURY =
            new AccountId("nation:" + NATION_ID.value() + ":treasury");
    private static final AccountId RECIPIENT =
            new AccountId("player:33333333-3333-3333-3333-333333333333");

    @TempDir Path temporaryDirectory;

    @Test
    void statusListsBothRecoveryWindowsWithoutCreatingOrAdvancingWork() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget awaitingBudget = approvedBudget(database, "awaiting");
            BudgetDisbursementApproval awaiting = approval(
                    database,
                    SERVICE,
                    "approved-without-payment",
                    awaitingBudget.budgetId());
            Budget preparedBudget = approvedBudget(database, "prepared");
            BudgetDisbursementApproval preparedApproval = approval(
                    database,
                    SERVICE,
                    "prepared-payment",
                    preparedBudget.budgetId());
            PreparedBudgetDisbursementPayment prepared =
                    new BudgetDisbursementPaymentCoordinator(
                                    database,
                                    ignored -> {},
                                    authorization -> authorization.openSession(
                                            BudgetDisbursementPaymentServiceProvisioner
                                                    .SERVICE_IDENTITY,
                                            "civiceconomy"))
                            .prepare(preparedApproval.approvalRequestId());

            BudgetDisbursementRecoveryStatus status =
                    new BudgetDisbursementRecoveryInspection(database, CLOCK)
                            .status(SERVICE);

            assertEquals(java.util.List.of(awaiting), status.approvedWithoutPayment());
            assertEquals(1, status.incompletePayments().size());
            assertEquals(
                    prepared.transaction().transactionId(),
                    status.incompletePayments().getFirst().transactionId());
            assertEquals(
                    TransactionState.PREPARED,
                    status.incompletePayments().getFirst().state());
            assertEquals(
                    "APPROVED",
                    new BudgetDisbursementApprovalRegistry(database, CLOCK)
                            .find(awaiting.approvalRequestId())
                            .state());
            assertEquals(
                    TransactionState.PREPARED,
                    PaymentCoordinator.toTransaction(database.paymentTransaction(
                                    prepared.transaction().transactionId()))
                            .state());
        }
    }

    private static BudgetDisbursementApproval approval(
            CivicDatabase database,
            ServiceIdentity serviceIdentity,
            String requestId,
            UUID budgetId) {
        return new BudgetDisbursementApprovalRegistry(database, CLOCK)
                .initiate(new InitiateBudgetDisbursementApproval(
                        serviceIdentity,
                        requestId,
                        NATION_ID,
                        budgetId,
                        RECIPIENT,
                        MoneyAmount.ofMinorUnits(100L),
                        ACTOR,
                        "Recovery inspection " + requestId));
    }

    private static Budget approvedBudget(CivicDatabase database, String suffix) {
        UUID budgetId = UUID.randomUUID();
        database.createBudget(
                budgetId,
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "create-" + suffix + "-budget",
                TREASURY.value(),
                100L,
                "PUBLIC_WORKS",
                "Recovery inspection Budget",
                NOW.plus(Duration.ofDays(1L)).toEpochMilli());
        return FiscalLedger.toBudget(database.approveBudget(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "approve-" + suffix + "-budget",
                budgetId,
                ACTOR,
                "Approve recovery inspection Budget",
                NOW.minusSeconds(1L).toEpochMilli()));
    }

    private static void registerNation(CivicDatabase database) {
        database.registerNation(
                NATION_ID.value(),
                "budget-disbursement-recovery-inspection",
                "register-budget-disbursement-recovery-inspection-nation",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                NOW.minusSeconds(60L).toEpochMilli());
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("budget-disbursement-recovery-inspection.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("55555555-5555-5555-5555-555555555555"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
