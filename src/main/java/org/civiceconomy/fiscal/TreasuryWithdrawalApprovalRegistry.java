package org.civiceconomy.fiscal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTreasuryWithdrawalApproval;

public final class TreasuryWithdrawalApprovalRegistry {
    private final CivicDatabase database;
    private final Clock clock;
    private final WithdrawalApprovalPolicyRegistry policies;

    public TreasuryWithdrawalApprovalRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
        this.policies = new WithdrawalApprovalPolicyRegistry(database, clock);
    }

    public TreasuryWithdrawalApproval initiate(ConfirmTreasuryWithdrawal request) {
        StoredTreasuryWithdrawalApproval replay = database.treasuryWithdrawalApproval(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toApproval(replay);
        }
        WithdrawalApprovalPolicyVersion policy = policies.current(
                request.nationId(), clock.instant());
        return toApproval(database.createTreasuryWithdrawalApproval(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.sourceAccount().value(),
                request.actorPlayerId(),
                request.amount().minorUnits(),
                request.reason(),
                policy.policyId(),
                policy.requiredApprovals(request.amount()),
                UUID.randomUUID(),
                request.actorPlayerId(),
                "Initiated Treasury Withdrawal",
                clock.millis()));
    }

    public TreasuryWithdrawalApproval approve(ApproveTreasuryWithdrawal request) {
        StoredTreasuryWithdrawalApproval replay = database.treasuryWithdrawalApprovalVote(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requireApprovalPayload(replay, request);
            return toApproval(replay);
        }
        StoredTreasuryWithdrawalApproval existing = database.treasuryWithdrawalApproval(
                request.approvalRequestId());
        if (existing == null) {
            throw new IllegalArgumentException(
                    "Unknown Treasury Withdrawal approval " + request.approvalRequestId());
        }
        if (!existing.serviceIdentity().equals(request.serviceIdentity().value())) {
            throw new SecurityException(
                    "Treasury Withdrawal approval belongs to another Service Identity");
        }
        if (existing.votes().stream().anyMatch(
                vote -> vote.approverPlayerId().equals(request.approverPlayerId()))) {
            throw new DuplicateWithdrawalApproverException(
                    request.approvalRequestId(), request.approverPlayerId());
        }
        if (!existing.state().equals("PENDING")) {
            throw new IllegalStateException(
                    "Treasury Withdrawal approval is already " + existing.state());
        }
        return toApproval(database.approveTreasuryWithdrawal(
                request.approvalRequestId(),
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.approverPlayerId(),
                request.reason(),
                clock.millis()));
    }

    public TreasuryWithdrawalApproval find(UUID approvalRequestId) {
        StoredTreasuryWithdrawalApproval stored =
                database.treasuryWithdrawalApproval(approvalRequestId);
        if (stored == null) {
            throw new IllegalArgumentException(
                    "Unknown Treasury Withdrawal approval " + approvalRequestId);
        }
        return toApproval(stored);
    }

    private static void requirePayload(
            StoredTreasuryWithdrawalApproval stored,
            ConfirmTreasuryWithdrawal request) {
        if (!stored.nationId().equals(request.nationId().value())
                || !stored.sourceAccount().equals(request.sourceAccount().value())
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || stored.amountMinorUnits() != request.amount().minorUnits()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static void requireApprovalPayload(
            StoredTreasuryWithdrawalApproval stored,
            ApproveTreasuryWithdrawal request) {
        var vote = stored.votes().stream()
                .filter(candidate -> candidate.serviceIdentity()
                        .equals(request.serviceIdentity().value()))
                .filter(candidate -> candidate.requestId().equals(request.requestId()))
                .findFirst()
                .orElseThrow();
        if (!stored.approvalRequestId().equals(request.approvalRequestId())
                || !vote.approverPlayerId().equals(request.approverPlayerId())
                || !vote.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static TreasuryWithdrawalApproval toApproval(
            StoredTreasuryWithdrawalApproval stored) {
        return new TreasuryWithdrawalApproval(
                stored.approvalRequestId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new NationId(stored.nationId()),
                new AccountId(stored.sourceAccount()),
                stored.actorPlayerId(),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                stored.reason(),
                stored.policyId(),
                stored.requiredApprovals(),
                stored.votes().stream()
                        .map(vote -> vote.approverPlayerId())
                        .toList(),
                stored.state(),
                Instant.ofEpochMilli(stored.initiatedAtEpochMillis()),
                stored.approvedAtEpochMillis() == null
                        ? null
                        : Instant.ofEpochMilli(stored.approvedAtEpochMillis()));
    }
}
