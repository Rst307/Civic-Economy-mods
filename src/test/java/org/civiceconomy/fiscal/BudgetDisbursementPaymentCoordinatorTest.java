package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetDisbursementPaymentCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-17T05:00:00Z");
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
    void approvedDisbursementPaysExactRecipientOnceAndExecutesApprovalAtomically() {
        List<ExternalPayment> effects = new ArrayList<>();
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget budget = approvedBudget(database);
            BudgetDisbursementApproval approval =
                    new BudgetDisbursementApprovalRegistry(
                                    database, Clock.fixed(NOW, ZoneOffset.UTC))
                            .initiate(new InitiateBudgetDisbursementApproval(
                                    NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY,
                                    "pay-first-milestone",
                                    NATION_ID,
                                    budget.budgetId(),
                                    RECIPIENT,
                                    MoneyAmount.ofMinorUnits(100L),
                                    ACTOR,
                                    "Pay first public works milestone"));
            BudgetDisbursementPaymentCoordinator coordinator =
                    new BudgetDisbursementPaymentCoordinator(
                            database,
                            effects::add,
                            authorization -> authorization.openSession(
                                    BudgetDisbursementPaymentServiceProvisioner
                                            .SERVICE_IDENTITY,
                                    "civiceconomy"));

            PaymentTransaction committed = coordinator.pay(approval.approvalRequestId());

            assertEquals(TransactionState.CIVIC_COMMITTED, committed.state());
            assertEquals(TREASURY, committed.sourceAccount());
            assertEquals(RECIPIENT, committed.recipientAccount());
            assertEquals(MoneyAmount.ofMinorUnits(100L), committed.amount());
            assertEquals(1, effects.size());
            assertEquals(committed.transactionId(), effects.getFirst().transactionId());
            Budget advanced = FiscalLedger.toBudget(database.budget(budget.budgetId()));
            assertEquals(BudgetState.PARTIALLY_SPENT, advanced.state());
            assertEquals(MoneyAmount.ofMinorUnits(100L), advanced.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(200L), advanced.remainingAmount());
            BudgetDisbursementApproval executed =
                    new BudgetDisbursementApprovalRegistry(
                                    database, Clock.fixed(NOW, ZoneOffset.UTC))
                            .find(approval.approvalRequestId());
            assertEquals("EXECUTED", executed.state());
            assertEquals(committed, coordinator.pay(approval.approvalRequestId()));
            assertEquals(1, effects.size());
        }
    }

    @Test
    void pendingDisbursementFailsBeforePaymentServiceProvisioningOrExternalEffect() {
        List<ExternalPayment> effects = new ArrayList<>();
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget budget = approvedBudget(database);
            new BudgetDisbursementApprovalPolicyRegistry(
                            database,
                            Clock.fixed(NOW.minusSeconds(120L), ZoneOffset.UTC))
                    .schedule(new ScheduleBudgetDisbursementApprovalPolicy(
                            new ServiceIdentity("civiceconomy-budget-governance"),
                            "schedule-pending-payment-policy",
                            NATION_ID,
                            ACTOR,
                            List.of(new BudgetDisbursementApprovalTier(MoneyAmount.ZERO, 2)),
                            Duration.ofDays(7L),
                            NOW.minusSeconds(60L),
                            "Require two Citizens before payment"));
            BudgetDisbursementApproval pending =
                    new BudgetDisbursementApprovalRegistry(
                                    database, Clock.fixed(NOW, ZoneOffset.UTC))
                            .initiate(new InitiateBudgetDisbursementApproval(
                                    NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY,
                                    "pending-payment",
                                    NATION_ID,
                                    budget.budgetId(),
                                    RECIPIENT,
                                    MoneyAmount.ofMinorUnits(100L),
                                    ACTOR,
                                    "Pending public works milestone"));
            BudgetDisbursementPaymentCoordinator coordinator =
                    new BudgetDisbursementPaymentCoordinator(
                            database,
                            effects::add,
                            authorization -> authorization.openSession(
                                    BudgetDisbursementPaymentServiceProvisioner
                                            .SERVICE_IDENTITY,
                                    "civiceconomy"));

            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.pay(pending.approvalRequestId()));

            assertNull(database.fiscalService(
                    BudgetDisbursementPaymentServiceProvisioner.SERVICE_IDENTITY.value()));
            assertNull(database.paymentTransaction("pending-payment"));
            assertEquals(0, effects.size());
        }
    }

    @Test
    void externalAppliedCrashRecoveryExecutesApprovalWithoutSecondEconomicEffect() {
        Set<UUID> applied = new HashSet<>();
        ExternalPayments idempotentExternal = payment -> applied.add(payment.transactionId());
        UUID approvalRequestId;
        UUID budgetId;
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget budget = approvedBudget(database);
            budgetId = budget.budgetId();
            BudgetDisbursementApproval approval =
                    new BudgetDisbursementApprovalRegistry(
                                    database, Clock.fixed(NOW, ZoneOffset.UTC))
                            .initiate(new InitiateBudgetDisbursementApproval(
                                    NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY,
                                    "recover-milestone-payment",
                                    NATION_ID,
                                    budgetId,
                                    RECIPIENT,
                                    MoneyAmount.ofMinorUnits(100L),
                                    ACTOR,
                                    "Recover public works milestone"));
            approvalRequestId = approval.approvalRequestId();
            BudgetDisbursementPaymentCoordinator coordinator =
                    new BudgetDisbursementPaymentCoordinator(
                            database,
                            idempotentExternal,
                            authorization -> authorization.openSession(
                                    BudgetDisbursementPaymentServiceProvisioner
                                            .SERVICE_IDENTITY,
                                    "civiceconomy"));
            PreparedBudgetDisbursementPayment prepared =
                    coordinator.prepare(approvalRequestId);

            coordinator.applyExternal(prepared);
            database.markExternalApplied(prepared.transaction().transactionId());

            assertEquals(1, applied.size());
            assertEquals(
                    "APPROVED",
                    new BudgetDisbursementApprovalRegistry(
                                    database, Clock.fixed(NOW, ZoneOffset.UTC))
                            .find(approvalRequestId)
                            .state());
        }

        try (CivicDatabase reopened = database()) {
            new PaymentCoordinator(reopened, idempotentExternal).recoverIncomplete();

            assertEquals(1, applied.size());
            assertEquals(
                    "EXECUTED",
                    new BudgetDisbursementApprovalRegistry(
                                    reopened, Clock.fixed(NOW, ZoneOffset.UTC))
                            .find(approvalRequestId)
                            .state());
            Budget recovered = FiscalLedger.toBudget(reopened.budget(budgetId));
            assertEquals(BudgetState.PARTIALLY_SPENT, recovered.state());
            assertEquals(MoneyAmount.ofMinorUnits(100L), recovered.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(200L), recovered.remainingAmount());
        }
    }

    @Test
    void approvedDecisionWithoutPaymentIsPreparedAndCommittedAfterRestart() {
        UUID approvalRequestId;
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget budget = approvedBudget(database);
            BudgetDisbursementApproval approval =
                    new BudgetDisbursementApprovalRegistry(
                                    database, Clock.fixed(NOW, ZoneOffset.UTC))
                            .initiate(new InitiateBudgetDisbursementApproval(
                                    NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY,
                                    "approved-before-payment-preparation",
                                    NATION_ID,
                                    budget.budgetId(),
                                    RECIPIENT,
                                    MoneyAmount.ofMinorUnits(100L),
                                    ACTOR,
                                    "Decision committed before process loss"));
            approvalRequestId = approval.approvalRequestId();

            assertEquals("APPROVED", approval.state());
            assertNull(database.paymentTransaction(
                    "approved-before-payment-preparation"));
        }

        List<ExternalPayment> effects = new ArrayList<>();
        try (CivicDatabase reopened = database()) {
            BudgetDisbursementPaymentCoordinator recovery =
                    new BudgetDisbursementPaymentCoordinator(
                            reopened,
                            effects::add,
                            authorization -> authorization.openSession(
                                    BudgetDisbursementPaymentServiceProvisioner
                                            .SERVICE_IDENTITY,
                                    "civiceconomy"));

            List<PreparedBudgetDisbursementPayment> prepared =
                    recovery.prepareApprovedPending();

            assertEquals(1, prepared.size());
            assertEquals(approvalRequestId, prepared.getFirst().approvalRequestId());
            recovery.applyExternal(prepared.getFirst());
            recovery.commit(prepared.getFirst());
            assertEquals(1, effects.size());
            assertEquals(List.of(), recovery.prepareApprovedPending());
            assertEquals(
                    "EXECUTED",
                    new BudgetDisbursementApprovalRegistry(
                                    reopened, Clock.fixed(NOW, ZoneOffset.UTC))
                            .find(approvalRequestId)
                            .state());
        }
    }

    private static Budget approvedBudget(CivicDatabase database) {
        UUID budgetId = UUID.randomUUID();
        database.createBudget(
                budgetId,
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "create-payment-budget",
                TREASURY.value(),
                300L,
                "PUBLIC_WORKS",
                "Budget payment fixture",
                NOW.plus(Duration.ofDays(30L)).toEpochMilli());
        return FiscalLedger.toBudget(database.approveBudget(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "approve-payment-budget",
                budgetId,
                ACTOR,
                "Approve payment Budget",
                NOW.minusSeconds(60L).toEpochMilli()));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("budget-disbursement-payment.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private static void registerNation(CivicDatabase database) {
        database.registerNation(
                NATION_ID.value(),
                "budget-disbursement-payment-test",
                "register-budget-disbursement-payment-nation",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                NOW.minusSeconds(120L).toEpochMilli());
    }
}
