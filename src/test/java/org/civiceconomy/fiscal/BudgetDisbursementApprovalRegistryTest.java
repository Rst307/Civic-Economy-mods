package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetDisbursementApprovalRegistryTest {
    private static final Instant SCHEDULED_AT = Instant.parse("2026-07-17T02:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-18T02:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-budget-disbursement");
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID INITIATOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECOND_APPROVER =
            UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final AccountId TREASURY =
            new AccountId("nation:" + NATION_ID.value() + ":treasury");
    private static final AccountId RECIPIENT =
            new AccountId("player:33333333-3333-3333-3333-333333333333");

    @TempDir Path temporaryDirectory;

    @Test
    void requestPinsPolicyWithoutMovingOrConsumingBudgetFunds() {
        UUID budgetId;
        UUID reservationId;
        BudgetDisbursementApproval initiated;
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget approved = approvedBudget(database);
            budgetId = approved.budgetId();
            reservationId = database.escrow(approved.escrowId().orElseThrow()).reservationId();
            new BudgetDisbursementApprovalPolicyRegistry(
                            database, Clock.fixed(SCHEDULED_AT, ZoneOffset.UTC))
                    .schedule(new ScheduleBudgetDisbursementApprovalPolicy(
                            new ServiceIdentity("civiceconomy-budget-governance"),
                            "schedule-two-person-disbursement",
                            NATION_ID,
                            INITIATOR,
                            List.of(new BudgetDisbursementApprovalTier(
                                    MoneyAmount.ZERO, 2)),
                            Duration.ofDays(7L),
                            EFFECTIVE_AT,
                            "Require two Citizens for Budget disbursement"));
            BudgetDisbursementApprovalRegistry approvals =
                    new BudgetDisbursementApprovalRegistry(
                            database, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC));

            initiated = approvals.initiate(new InitiateBudgetDisbursementApproval(
                    SERVICE,
                    "request-builder-payment",
                    NATION_ID,
                    budgetId,
                    RECIPIENT,
                    MoneyAmount.ofMinorUnits(100L),
                    INITIATOR,
                    "First public works milestone"));

            assertEquals("PENDING", initiated.state());
            assertEquals(2, initiated.requiredApprovals());
            assertEquals(List.of(INITIATOR), initiated.approverPlayerIds());
            assertEquals(MoneyAmount.ofMinorUnits(100L), initiated.amount());
            assertEquals(RECIPIENT, initiated.recipientAccount());
            assertEquals("APPROVED", database.budget(budgetId).state());
            assertEquals("ACTIVE", database.reservationRecord(reservationId).state());
            assertEquals(0L, database.reservationRecord(reservationId).settledMinorUnits());
            assertNull(database.paymentTransaction("request-builder-payment"));
        }

        try (CivicDatabase reopened = database()) {
            BudgetDisbursementApproval replay =
                    new BudgetDisbursementApprovalRegistry(
                                    reopened, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC))
                            .find(initiated.approvalRequestId());

            assertEquals(initiated, replay);
            assertEquals("APPROVED", reopened.budget(budgetId).state());
            assertEquals("ACTIVE", reopened.reservationRecord(reservationId).state());
        }
    }

    @Test
    void pendingApprovalsCannotOverbookOneBudgetRemainder() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget approved = approvedBudget(database);
            BudgetDisbursementApprovalRegistry approvals =
                    new BudgetDisbursementApprovalRegistry(
                            database, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC));
            approvals.initiate(new InitiateBudgetDisbursementApproval(
                    SERVICE,
                    "reserve-first-milestone",
                    NATION_ID,
                    approved.budgetId(),
                    RECIPIENT,
                    MoneyAmount.ofMinorUnits(200L),
                    INITIATOR,
                    "Reserve first milestone authority"));

            assertThrows(
                    InsufficientAvailableBalanceException.class,
                    () -> approvals.initiate(new InitiateBudgetDisbursementApproval(
                            SERVICE,
                            "overbook-second-milestone",
                            NATION_ID,
                            approved.budgetId(),
                            new AccountId(
                                    "player:55555555-5555-5555-5555-555555555555"),
                            MoneyAmount.ofMinorUnits(200L),
                            INITIATOR,
                            "Cannot overbook the remaining Budget")));

            assertNull(database.budgetDisbursementApproval(
                    SERVICE.value(), "overbook-second-milestone"));
            assertEquals("APPROVED", database.budget(approved.budgetId()).state());
            assertEquals(
                    0L,
                    database.reservationRecord(
                                    database.escrow(approved.escrowId().orElseThrow())
                                            .reservationId())
                            .settledMinorUnits());
        }
    }

    @Test
    void distinctSecondCitizenApprovalSurvivesRestartWithoutExecutingPayment() {
        BudgetDisbursementApproval pending;
        UUID reservationId;
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget approved = approvedBudget(database);
            reservationId = database.escrow(approved.escrowId().orElseThrow()).reservationId();
            scheduleTwoPersonPolicy(database);
            pending = new BudgetDisbursementApprovalRegistry(
                            database, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC))
                    .initiate(new InitiateBudgetDisbursementApproval(
                            SERVICE,
                            "request-restart-payment",
                            NATION_ID,
                            approved.budgetId(),
                            RECIPIENT,
                            MoneyAmount.ofMinorUnits(100L),
                            INITIATOR,
                            "Restart-safe public works milestone"));
        }

        Instant secondVoteAt = EFFECTIVE_AT.plusSeconds(60L);
        try (CivicDatabase reopened = database()) {
            BudgetDisbursementApprovalRegistry approvals =
                    new BudgetDisbursementApprovalRegistry(
                            reopened, Clock.fixed(secondVoteAt, ZoneOffset.UTC));
            ApproveBudgetDisbursementApproval vote =
                    new ApproveBudgetDisbursementApproval(
                            SERVICE,
                            "approve-restart-payment",
                            pending.approvalRequestId(),
                            SECOND_APPROVER,
                            "Verified milestone evidence");

            BudgetDisbursementApproval approved = approvals.approve(vote);

            assertEquals("APPROVED", approved.state());
            assertEquals(secondVoteAt, approved.approvedAt());
            assertEquals(List.of(INITIATOR, SECOND_APPROVER), approved.approverPlayerIds());
            assertEquals(approved, approvals.approve(vote));
            assertEquals("ACTIVE", reopened.reservationRecord(reservationId).state());
            assertEquals(0L, reopened.reservationRecord(reservationId).settledMinorUnits());
            assertNull(reopened.paymentTransaction("request-restart-payment"));
        }
    }

    @Test
    void expiredPendingApprovalFreesBudgetAuthorizationCapacityWithoutPayment() {
        Instant expiresAt = EFFECTIVE_AT.plus(Duration.ofDays(7L));
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget budget = approvedBudget(database);
            scheduleTwoPersonPolicy(database);
            BudgetDisbursementApproval pending =
                    new BudgetDisbursementApprovalRegistry(
                                    database, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC))
                            .initiate(new InitiateBudgetDisbursementApproval(
                                    SERVICE,
                                    "expiring-disbursement",
                                    NATION_ID,
                                    budget.budgetId(),
                                    RECIPIENT,
                                    MoneyAmount.ofMinorUnits(200L),
                                    INITIATOR,
                                    "Expiring public works authority"));

            BudgetDisbursementApprovalRegistry afterDeadline =
                    new BudgetDisbursementApprovalRegistry(
                            database,
                            Clock.fixed(expiresAt.plusMillis(1L), ZoneOffset.UTC));
            BudgetDisbursementApproval expired = afterDeadline.expirePending().getFirst();
            BudgetDisbursementApproval replacement = afterDeadline.initiate(
                    new InitiateBudgetDisbursementApproval(
                            SERVICE,
                            "replacement-disbursement",
                            NATION_ID,
                            budget.budgetId(),
                            new AccountId(
                                    "player:77777777-7777-7777-7777-777777777777"),
                            MoneyAmount.ofMinorUnits(200L),
                            INITIATOR,
                            "Replacement public works authority"));

            assertEquals(pending.approvalRequestId(), expired.approvalRequestId());
            assertEquals("EXPIRED", expired.state());
            assertEquals(expiresAt, expired.expiresAt());
            assertEquals(expiresAt.plusMillis(1L), expired.expiredAt());
            assertEquals("PENDING", replacement.state());
            assertNull(database.paymentTransaction("expiring-disbursement"));
            assertEquals("APPROVED", database.budget(budget.budgetId()).state());
        }
    }

    @Test
    void pendingApprovalRejectsVotesAtItsPinnedExpiryBeforeTheSweepRuns() {
        Instant expiresAt = EFFECTIVE_AT.plus(Duration.ofDays(7L));
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget budget = approvedBudget(database);
            scheduleTwoPersonPolicy(database);
            BudgetDisbursementApproval pending =
                    new BudgetDisbursementApprovalRegistry(
                                    database, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC))
                            .initiate(new InitiateBudgetDisbursementApproval(
                                    SERVICE,
                                    "deadline-disbursement",
                                    NATION_ID,
                                    budget.budgetId(),
                                    RECIPIENT,
                                    MoneyAmount.ofMinorUnits(100L),
                                    INITIATOR,
                                    "Deadline-bound public works authority"));
            BudgetDisbursementApprovalRegistry atDeadline =
                    new BudgetDisbursementApprovalRegistry(
                            database, Clock.fixed(expiresAt, ZoneOffset.UTC));
            ApproveBudgetDisbursementApproval lateVote =
                    new ApproveBudgetDisbursementApproval(
                            SERVICE,
                            "late-deadline-vote",
                            pending.approvalRequestId(),
                            SECOND_APPROVER,
                            "Too late to approve");

            assertThrows(IllegalStateException.class, () -> atDeadline.approve(lateVote));

            BudgetDisbursementApproval unchanged = atDeadline.find(pending.approvalRequestId());
            assertEquals("PENDING", unchanged.state());
            assertEquals(List.of(INITIATOR), unchanged.approverPlayerIds());
            assertNull(database.budgetDisbursementApprovalVote(
                    SERVICE.value(), "late-deadline-vote"));
            assertNull(database.paymentTransaction("deadline-disbursement"));
        }
    }

    @Test
    void pendingApprovalCancellationIsAuditedAndFreesAuthorizationCapacity() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget budget = approvedBudget(database);
            scheduleTwoPersonPolicy(database);
            BudgetDisbursementApprovalRegistry approvals =
                    new BudgetDisbursementApprovalRegistry(
                            database, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC));
            BudgetDisbursementApproval pending = approvals.initiate(
                    new InitiateBudgetDisbursementApproval(
                            SERVICE,
                            "cancelled-disbursement",
                            NATION_ID,
                            budget.budgetId(),
                            RECIPIENT,
                            MoneyAmount.ofMinorUnits(200L),
                            INITIATOR,
                            "Cancelled public works authority"));
            CancelBudgetDisbursementApproval cancellation =
                    new CancelBudgetDisbursementApproval(
                            SERVICE,
                            "cancel-disbursement-approval",
                            pending.approvalRequestId(),
                            INITIATOR,
                            "Project procurement was withdrawn");

            BudgetDisbursementApproval cancelled = approvals.cancel(cancellation);
            BudgetDisbursementApproval replacement = approvals.initiate(
                    new InitiateBudgetDisbursementApproval(
                            SERVICE,
                            "replacement-after-cancellation",
                            NATION_ID,
                            budget.budgetId(),
                            new AccountId(
                                    "player:77777777-7777-7777-7777-777777777777"),
                            MoneyAmount.ofMinorUnits(200L),
                            INITIATOR,
                            "Replacement public works authority"));

            assertEquals(cancelled, approvals.cancel(cancellation));
            assertEquals("CANCELLED", cancelled.state());
            assertEquals(INITIATOR, cancelled.cancelledByPlayerId());
            assertEquals("Project procurement was withdrawn", cancelled.cancellationReason());
            assertEquals(EFFECTIVE_AT, cancelled.cancelledAt());
            assertEquals("PENDING", replacement.state());
            assertNull(database.paymentTransaction("cancelled-disbursement"));
            assertEquals("APPROVED", database.budget(budget.budgetId()).state());
            assertEquals(
                    "ACTIVE",
                    database.reservationRecord(
                                    database.escrow(budget.escrowId().orElseThrow())
                                            .reservationId())
                            .state());
            assertThrows(
                    IdempotencyConflictException.class,
                    () -> approvals.cancel(new CancelBudgetDisbursementApproval(
                            SERVICE,
                            "cancel-disbursement-approval",
                            pending.approvalRequestId(),
                            INITIATOR,
                            "Changed cancellation reason")));
            assertThrows(
                    IllegalStateException.class,
                    () -> approvals.approve(new ApproveBudgetDisbursementApproval(
                            SERVICE,
                            "approve-cancelled-disbursement",
                            pending.approvalRequestId(),
                            SECOND_APPROVER,
                            "Cancelled approval cannot be revived")));
            assertNull(database.budgetDisbursementApprovalVote(
                    SERVICE.value(), "approve-cancelled-disbursement"));
        }
    }

    @Test
    void tieredPolicyReplayIsStrictAndNewApprovalsPinTheirAmountTier() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget budget = approvedBudget(database);
            BudgetDisbursementApprovalPolicyRegistry policies =
                    new BudgetDisbursementApprovalPolicyRegistry(
                            database, Clock.fixed(SCHEDULED_AT, ZoneOffset.UTC));
            ScheduleBudgetDisbursementApprovalPolicy request =
                    new ScheduleBudgetDisbursementApprovalPolicy(
                            new ServiceIdentity("civiceconomy-budget-governance"),
                            "schedule-tiered-disbursement",
                            NATION_ID,
                            INITIATOR,
                            List.of(
                                    new BudgetDisbursementApprovalTier(MoneyAmount.ZERO, 1),
                                    new BudgetDisbursementApprovalTier(
                                            MoneyAmount.ofMinorUnits(100L), 2),
                                    new BudgetDisbursementApprovalTier(
                                            MoneyAmount.ofMinorUnits(250L), 3)),
                            Duration.ofDays(7L),
                            EFFECTIVE_AT,
                            "Scale approvals with procurement amount");
            BudgetDisbursementApprovalPolicyVersion scheduled = policies.schedule(request);
            BudgetDisbursementApprovalRegistry beforePolicy =
                    new BudgetDisbursementApprovalRegistry(
                            database,
                            Clock.fixed(EFFECTIVE_AT.minusMillis(1L), ZoneOffset.UTC));
            BudgetDisbursementApproval earlier = beforePolicy.initiate(
                    new InitiateBudgetDisbursementApproval(
                            SERVICE,
                            "before-tiered-policy",
                            NATION_ID,
                            budget.budgetId(),
                            RECIPIENT,
                            MoneyAmount.ofMinorUnits(50L),
                            INITIATOR,
                            "Small procurement before policy activation"));
            BudgetDisbursementApprovalRegistry afterPolicy =
                    new BudgetDisbursementApprovalRegistry(
                            database, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC));
            BudgetDisbursementApproval tiered = afterPolicy.initiate(
                    new InitiateBudgetDisbursementApproval(
                            SERVICE,
                            "after-tiered-policy",
                            NATION_ID,
                            budget.budgetId(),
                            new AccountId(
                                    "player:77777777-7777-7777-7777-777777777777"),
                            MoneyAmount.ofMinorUnits(200L),
                            INITIATOR,
                            "Mid-sized procurement after policy activation"));

            assertEquals(scheduled, policies.schedule(request));
            assertEquals(1, earlier.requiredApprovals());
            assertEquals(
                    new UUID(0L, 0L),
                    earlier.policyId());
            assertEquals(2, tiered.requiredApprovals());
            assertEquals(scheduled.policyId(), tiered.policyId());
            assertEquals(earlier, afterPolicy.find(earlier.approvalRequestId()));
            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleBudgetDisbursementApprovalPolicy(
                            request.serviceIdentity(),
                            request.requestId(),
                            request.nationId(),
                            request.actorPlayerId(),
                            List.of(new BudgetDisbursementApprovalTier(
                                    MoneyAmount.ZERO, 2)),
                            request.approvalLifetime(),
                            request.effectiveAt(),
                            request.reason())));
        }
    }

    @Test
    void approvedWithoutPaymentListsOnlyRecoverableExactServiceDecisions() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            Budget budget = approvedBudget(database);
            scheduleTwoPersonPolicy(database);
            BudgetDisbursementApprovalRegistry approvals =
                    new BudgetDisbursementApprovalRegistry(
                            database, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC));
            BudgetDisbursementApproval approved = approvals.initiate(
                    new InitiateBudgetDisbursementApproval(
                            SERVICE,
                            "recover-approved-disbursement",
                            NATION_ID,
                            budget.budgetId(),
                            RECIPIENT,
                            MoneyAmount.ofMinorUnits(100L),
                            INITIATOR,
                            "Approved before process loss"));
            approved = approvals.approve(new ApproveBudgetDisbursementApproval(
                    SERVICE,
                    "recover-approved-second-vote",
                    approved.approvalRequestId(),
                    SECOND_APPROVER,
                    "Second approval committed before process loss"));
            approvals.initiate(new InitiateBudgetDisbursementApproval(
                    SERVICE,
                    "still-pending-disbursement",
                    NATION_ID,
                    budget.budgetId(),
                    new AccountId(
                            "player:88888888-8888-8888-8888-888888888888"),
                    MoneyAmount.ofMinorUnits(100L),
                    INITIATOR,
                    "Still waiting for a second Citizen"));
            ServiceIdentity impostorService =
                    new ServiceIdentity("impostor-budget-disbursement");
            BudgetDisbursementApproval impostor = approvals.initiate(
                    new InitiateBudgetDisbursementApproval(
                            impostorService,
                            "impostor-approved-disbursement",
                            NATION_ID,
                            budget.budgetId(),
                            new AccountId(
                                    "player:99999999-9999-9999-9999-999999999999"),
                            MoneyAmount.ofMinorUnits(100L),
                            INITIATOR,
                            "Another Service Identity decision"));
            approvals.approve(new ApproveBudgetDisbursementApproval(
                    impostorService,
                    "impostor-approved-second-vote",
                    impostor.approvalRequestId(),
                    SECOND_APPROVER,
                    "Second impostor-service vote"));

            assertEquals(
                    List.of(approved),
                    approvals.approvedWithoutPayment(SERVICE));

            new BudgetDisbursementPaymentCoordinator(
                            database,
                            ignored -> {},
                            authorization -> authorization.openSession(
                                    BudgetDisbursementPaymentServiceProvisioner
                                            .SERVICE_IDENTITY,
                                    "civiceconomy"))
                    .prepare(approved.approvalRequestId());

            assertEquals(List.of(), approvals.approvedWithoutPayment(SERVICE));
        }
    }

    private static Budget approvedBudget(CivicDatabase database) {
        UUID budgetId = UUID.randomUUID();
        database.createBudget(
                budgetId,
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "create-disbursement-budget",
                TREASURY.value(),
                300L,
                "PUBLIC_WORKS",
                "Budget disbursement fixture",
                EFFECTIVE_AT.plus(Duration.ofDays(30L)).toEpochMilli());
        return FiscalLedger.toBudget(database.approveBudget(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                "approve-disbursement-budget",
                budgetId,
                INITIATOR,
                "Approve disbursement fixture",
                EFFECTIVE_AT.minusSeconds(60L).toEpochMilli()));
    }

    private static void scheduleTwoPersonPolicy(CivicDatabase database) {
        new BudgetDisbursementApprovalPolicyRegistry(
                        database, Clock.fixed(SCHEDULED_AT, ZoneOffset.UTC))
                .schedule(new ScheduleBudgetDisbursementApprovalPolicy(
                        new ServiceIdentity("civiceconomy-budget-governance"),
                        "schedule-restart-two-person-disbursement",
                        NATION_ID,
                        INITIATOR,
                        List.of(new BudgetDisbursementApprovalTier(MoneyAmount.ZERO, 2)),
                        Duration.ofDays(7L),
                        EFFECTIVE_AT,
                        "Require two Citizens for restart-safe disbursement"));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("budget-disbursement-approval.sqlite3"),
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
                "budget-disbursement-test",
                "register-budget-disbursement-nation",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                SCHEDULED_AT.minusSeconds(60L).toEpochMilli());
    }
}
