package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreasuryWithdrawalApprovalRegistryTest {
    private static final Instant SCHEDULED_AT = Instant.parse("2026-07-16T06:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-23T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-treasury-withdrawal");
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final NationId OTHER_NATION_ID =
            new NationId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    private static final AccountId TREASURY =
            new AccountId("nation:" + NATION_ID.value() + ":treasury");
    private static final UUID INITIATOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECOND_APPROVER =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @TempDir Path temporaryDirectory;

    @Test
    void requestPinsPolicyAndDistinctApprovalSurvivesRestart() {
        ConfirmTreasuryWithdrawal request = request("withdraw-two-person", 700L);
        TreasuryWithdrawalApproval pending;

        try (CivicDatabase database = database()) {
            registerNation(database);
            schedulePolicy(database, 2, EFFECTIVE_AT);
            TreasuryWithdrawalApprovalRegistry approvals = approvals(database, EFFECTIVE_AT);

            pending = approvals.initiate(request);

            assertEquals("PENDING", pending.state());
            assertEquals(2, pending.requiredApprovals());
            assertEquals(List.of(INITIATOR), pending.approverPlayerIds());
            assertEquals(pending, approvals.initiate(request));

            schedulePolicy(database, 3, EFFECTIVE_AT.plusSeconds(86_400L));
        }

        try (CivicDatabase reopened = database()) {
            TreasuryWithdrawalApprovalRegistry approvals = approvals(
                    reopened, EFFECTIVE_AT.plusSeconds(172_800L));
            TreasuryWithdrawalApproval replay = approvals.find(pending.approvalRequestId());
            assertEquals(2, replay.requiredApprovals());
            assertEquals("PENDING", replay.state());

            TreasuryWithdrawalApproval approved = approvals.approve(
                    new ApproveTreasuryWithdrawal(
                            SERVICE,
                            "approve-withdraw-two-person",
                            pending.approvalRequestId(),
                            SECOND_APPROVER,
                            "Second treasury officer approval"));

            assertEquals("APPROVED", approved.state());
            assertEquals(List.of(INITIATOR, SECOND_APPROVER), approved.approverPlayerIds());
            assertEquals(
                    approved,
                    approvals.approve(new ApproveTreasuryWithdrawal(
                            SERVICE,
                            "approve-withdraw-two-person",
                            pending.approvalRequestId(),
                            SECOND_APPROVER,
                            "Second treasury officer approval")));
            assertThrows(
                    DuplicateWithdrawalApproverException.class,
                    () -> approvals.approve(new ApproveTreasuryWithdrawal(
                            SERVICE,
                            "approve-withdraw-two-person-again",
                            pending.approvalRequestId(),
                            SECOND_APPROVER,
                            "Cannot count the same Citizen twice")));
        }
    }

    @Test
    void initiationReplayCannotChangeBoundWithdrawalPayload() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            schedulePolicy(database, 2, EFFECTIVE_AT);
            TreasuryWithdrawalApprovalRegistry approvals = approvals(database, EFFECTIVE_AT);
            approvals.initiate(request("withdraw-conflict", 700L));

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> approvals.initiate(request("withdraw-conflict", 701L)));
        }
    }

    @Test
    void inspectionIsLimitedToOneExactNation() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            registerOtherNation(database);
            TreasuryWithdrawalApprovalRegistry approvals = approvals(database, EFFECTIVE_AT);
            TreasuryWithdrawalApproval own = approvals.initiate(
                    request("withdraw-visible", 700L));
            TreasuryWithdrawalApproval foreign = approvals.initiate(
                    new ConfirmTreasuryWithdrawal(
                            SERVICE,
                            "withdraw-hidden",
                            OTHER_NATION_ID,
                            new AccountId("nation:" + OTHER_NATION_ID.value() + ":treasury"),
                            SECOND_APPROVER,
                            MoneyAmount.ofMinorUnits(800L),
                            "Foreign Nation cash withdrawal"));

            assertEquals(List.of(own), approvals.listForNation(NATION_ID));
            assertEquals(own, approvals.findForNation(own.approvalRequestId(), NATION_ID));
            assertThrows(
                    SecurityException.class,
                    () -> approvals.findForNation(foreign.approvalRequestId(), NATION_ID));
        }
    }

    @Test
    void inspectionExposesImmutableVoteAndExecutionAuditFacts() {
        Instant secondVoteAt = EFFECTIVE_AT.plusSeconds(60L);
        Instant executedAt = EFFECTIVE_AT.plusSeconds(180L);
        try (CivicDatabase database = database()) {
            registerNation(database);
            schedulePolicy(database, 2, EFFECTIVE_AT);
            TreasuryWithdrawalApproval pending = approvals(database, EFFECTIVE_AT)
                    .initiate(request("withdraw-audit", 700L));
            TreasuryWithdrawalApproval approved = approvals(database, secondVoteAt)
                    .approve(new ApproveTreasuryWithdrawal(
                            SERVICE,
                            "approve-withdraw-audit",
                            pending.approvalRequestId(),
                            SECOND_APPROVER,
                            "Verified payroll evidence"));
            UUID withdrawalId = UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc");
            database.prepareTreasuryWithdrawal(
                    withdrawalId,
                    SERVICE.value(),
                    approved.requestId(),
                    approved.approvalRequestId(),
                    NATION_ID.value(),
                    TREASURY.value(),
                    INITIATOR,
                    approved.amount().minorUnits(),
                    approved.reason(),
                    EFFECTIVE_AT.plusSeconds(120L).toEpochMilli());
            database.commitTreasuryWithdrawal(withdrawalId, executedAt.toEpochMilli());

            TreasuryWithdrawalApproval inspected = approvals(database, executedAt)
                    .findForNation(pending.approvalRequestId(), NATION_ID);

            assertEquals("Cash for public works payroll", inspected.reason());
            assertEquals("EXECUTED", inspected.state());
            assertEquals(executedAt, inspected.executedAt());
            assertEquals(2, inspected.votes().size());
            assertEquals(INITIATOR, inspected.votes().get(0).approverPlayerId());
            assertEquals("Initiated Treasury Withdrawal", inspected.votes().get(0).reason());
            assertEquals(EFFECTIVE_AT, inspected.votes().get(0).approvedAt());
            assertEquals(SECOND_APPROVER, inspected.votes().get(1).approverPlayerId());
            assertEquals("Verified payroll evidence", inspected.votes().get(1).reason());
            assertEquals(secondVoteAt, inspected.votes().get(1).approvedAt());
        }
    }

    private void schedulePolicy(CivicDatabase database, int required, Instant effectiveAt) {
        new WithdrawalApprovalPolicyRegistry(
                        database, Clock.fixed(SCHEDULED_AT, ZoneOffset.UTC))
                .schedule(new ScheduleWithdrawalApprovalPolicy(
                        new ServiceIdentity("civiceconomy-withdrawal-governance"),
                        "policy-" + required,
                        NATION_ID,
                        INITIATOR,
                        List.of(new WithdrawalApprovalTier(MoneyAmount.ZERO, required)),
                        effectiveAt,
                        "Withdrawal policy requiring " + required + " approvers"));
    }

    private TreasuryWithdrawalApprovalRegistry approvals(
            CivicDatabase database, Instant now) {
        return new TreasuryWithdrawalApprovalRegistry(
                database, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static ConfirmTreasuryWithdrawal request(String requestId, long amount) {
        return new ConfirmTreasuryWithdrawal(
                SERVICE,
                requestId,
                NATION_ID,
                TREASURY,
                INITIATOR,
                MoneyAmount.ofMinorUnits(amount),
                "Cash for public works payroll");
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("treasury-withdrawal-approval.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("55555555-5555-5555-5555-555555555555"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private static void registerNation(CivicDatabase database) {
        database.registerNation(
                NATION_ID.value(),
                "withdrawal-approval-test",
                "register-nation",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                SCHEDULED_AT.minusSeconds(60L).toEpochMilli());
    }

    private static void registerOtherNation(CivicDatabase database) {
        database.registerNation(
                OTHER_NATION_ID.value(),
                "withdrawal-approval-other-test",
                "register-other-nation",
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                SCHEDULED_AT.minusSeconds(60L).toEpochMilli());
    }
}
