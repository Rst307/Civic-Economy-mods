package org.civiceconomy.fiscal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredBudgetDisbursementApproval;

public final class BudgetDisbursementApprovalRegistry {
    private final CivicDatabase database;
    private final Clock clock;
    private final BudgetDisbursementApprovalPolicyRegistry policies;

    public BudgetDisbursementApprovalRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
        this.policies = new BudgetDisbursementApprovalPolicyRegistry(database, clock);
    }

    public BudgetDisbursementApproval initiate(
            InitiateBudgetDisbursementApproval request) {
        StoredBudgetDisbursementApproval replay = database.budgetDisbursementApproval(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toApproval(replay);
        }
        var budget = database.budgetForSourceAccount(
                request.budgetId(),
                "nation:" + request.nationId().value() + ":treasury");
        if (budget == null) {
            throw new SecurityException(
                    "Budget disbursement requires an exact own-Nation Budget");
        }
        if (!("APPROVED".equals(budget.state())
                || "PARTIALLY_SPENT".equals(budget.state()))) {
            throw new IllegalStateException(
                    "Budget is not available for disbursement: " + budget.state());
        }
        long remaining = Math.subtractExact(
                budget.amountMinorUnits(), budget.settledMinorUnits());
        if (request.amount().minorUnits() > remaining) {
            throw new IllegalArgumentException(
                    "Budget disbursement exceeds the remaining Reservation");
        }
        BudgetDisbursementApprovalPolicyVersion policy =
                policies.current(request.nationId(), clock.instant());
        return toApproval(database.createBudgetDisbursementApproval(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.budgetId(),
                request.recipientAccount().value(),
                request.amount().minorUnits(),
                request.actorPlayerId(),
                request.reason(),
                policy.policyId(),
                policy.requiredApprovals(request.amount()),
                UUID.randomUUID(),
                request.actorPlayerId(),
                "Initiated Budget Disbursement",
                clock.millis(),
                Math.addExact(clock.millis(), policy.approvalLifetime().toMillis())));
    }

    public BudgetDisbursementApproval find(UUID approvalRequestId) {
        StoredBudgetDisbursementApproval stored =
                database.budgetDisbursementApproval(approvalRequestId);
        if (stored == null) {
            throw new IllegalArgumentException(
                    "Unknown Budget Disbursement approval " + approvalRequestId);
        }
        return toApproval(stored);
    }

    public BudgetDisbursementApproval findForNation(
            UUID approvalRequestId, NationId nationId) {
        if (approvalRequestId == null || nationId == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval lookup cannot contain null values");
        }
        StoredBudgetDisbursementApproval stored =
                database.budgetDisbursementApproval(approvalRequestId);
        if (stored == null || !stored.nationId().equals(nationId.value())) {
            throw new SecurityException(
                    "Budget Disbursement approval is unavailable for the current Nation");
        }
        return toApproval(stored);
    }

    public java.util.List<BudgetDisbursementApproval> listForNation(NationId nationId) {
        if (nationId == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval Nation cannot be null");
        }
        return database.budgetDisbursementApprovals(nationId.value()).stream()
                .map(BudgetDisbursementApprovalRegistry::toApproval)
                .toList();
    }

    public BudgetDisbursementApproval approve(
            ApproveBudgetDisbursementApproval request) {
        StoredBudgetDisbursementApproval replay =
                database.budgetDisbursementApprovalVote(
                        request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            var vote = replay.votes().stream()
                    .filter(existing -> existing.serviceIdentity()
                                    .equals(request.serviceIdentity().value())
                            && existing.requestId().equals(request.requestId()))
                    .findFirst()
                    .orElseThrow();
            if (!replay.approvalRequestId().equals(request.approvalRequestId())
                    || !vote.approverPlayerId().equals(request.approverPlayerId())
                    || !vote.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toApproval(replay);
        }
        StoredBudgetDisbursementApproval existing =
                database.budgetDisbursementApproval(request.approvalRequestId());
        if (existing == null) {
            throw new IllegalArgumentException(
                    "Unknown Budget Disbursement approval "
                            + request.approvalRequestId());
        }
        if (!existing.serviceIdentity().equals(request.serviceIdentity().value())) {
            throw new SecurityException(
                    "Budget Disbursement approval belongs to another Service Identity");
        }
        if (clock.millis() >= existing.expiresAtEpochMillis()) {
            throw new IllegalStateException(
                    "Budget Disbursement approval has reached its pinned expiry");
        }
        if (existing.votes().stream().anyMatch(
                vote -> vote.approverPlayerId().equals(request.approverPlayerId()))) {
            throw new DuplicateBudgetDisbursementApproverException(
                    request.approvalRequestId(), request.approverPlayerId());
        }
        if (!"PENDING".equals(existing.state())) {
            throw new IllegalStateException(
                    "Budget Disbursement approval is already " + existing.state());
        }
        return toApproval(database.approveBudgetDisbursement(
                request.approvalRequestId(),
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.approverPlayerId(),
                request.reason(),
                clock.millis()));
    }

    public java.util.List<BudgetDisbursementApproval> expirePending() {
        long now = clock.millis();
        return database.expirePendingBudgetDisbursementApprovals(now, now).stream()
                .map(BudgetDisbursementApprovalRegistry::toApproval)
                .toList();
    }

    private static void requirePayload(
            StoredBudgetDisbursementApproval stored,
            InitiateBudgetDisbursementApproval request) {
        if (!stored.nationId().equals(request.nationId().value())
                || !stored.budgetId().equals(request.budgetId())
                || !stored.recipientAccount().equals(request.recipientAccount().value())
                || stored.amountMinorUnits() != request.amount().minorUnits()
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static BudgetDisbursementApproval toApproval(
            StoredBudgetDisbursementApproval stored) {
        return new BudgetDisbursementApproval(
                stored.approvalRequestId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new NationId(stored.nationId()),
                stored.budgetId(),
                new AccountId(stored.recipientAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                stored.actorPlayerId(),
                stored.reason(),
                stored.policyId(),
                stored.requiredApprovals(),
                stored.votes().stream()
                        .map(vote -> new BudgetDisbursementApprovalVote(
                                vote.voteId(),
                                new ServiceIdentity(vote.serviceIdentity()),
                                vote.requestId(),
                                vote.approverPlayerId(),
                                vote.reason(),
                                Instant.ofEpochMilli(vote.approvedAtEpochMillis())))
                        .toList(),
                stored.state(),
                Instant.ofEpochMilli(stored.initiatedAtEpochMillis()),
                stored.approvedAtEpochMillis() == null
                        ? null
                        : Instant.ofEpochMilli(stored.approvedAtEpochMillis()),
                stored.executedAtEpochMillis() == null
                        ? null
                        : Instant.ofEpochMilli(stored.executedAtEpochMillis()),
                Instant.ofEpochMilli(stored.expiresAtEpochMillis()),
                stored.expiredAtEpochMillis() == null
                        ? null
                        : Instant.ofEpochMilli(stored.expiredAtEpochMillis()));
    }
}
