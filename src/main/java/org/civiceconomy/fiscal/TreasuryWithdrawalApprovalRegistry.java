package org.civiceconomy.fiscal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTreasuryWithdrawalApproval;

public final class TreasuryWithdrawalApprovalRegistry {
    private static final Duration DEFAULT_APPROVAL_LIFETIME = Duration.ofDays(7);
    private final CivicDatabase database;
    private final Clock clock;
    private final Duration approvalLifetime;
    private final WithdrawalApprovalPolicyRegistry policies;

    public TreasuryWithdrawalApprovalRegistry(CivicDatabase database, Clock clock) {
        this(database, clock, DEFAULT_APPROVAL_LIFETIME);
    }

    public TreasuryWithdrawalApprovalRegistry(
            CivicDatabase database, Clock clock, Duration approvalLifetime) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
        if (approvalLifetime == null || approvalLifetime.isNegative()
                || approvalLifetime.isZero()) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval lifetime must be positive");
        }
        this.approvalLifetime = approvalLifetime;
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
                clock.millis(),
                Math.addExact(clock.millis(), approvalLifetime.toMillis())));
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

    public TreasuryWithdrawalApproval findForNation(
            UUID approvalRequestId, NationId nationId) {
        if (approvalRequestId == null || nationId == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval lookup cannot contain null values");
        }
        StoredTreasuryWithdrawalApproval stored =
                database.treasuryWithdrawalApproval(approvalRequestId);
        if (stored == null || !stored.nationId().equals(nationId.value())) {
            throw new SecurityException(
                    "Treasury Withdrawal approval is unavailable for the current Nation");
        }
        return toApproval(stored);
    }

    public List<TreasuryWithdrawalApproval> listForNation(NationId nationId) {
        if (nationId == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval Nation cannot be null");
        }
        return database.treasuryWithdrawalApprovals(nationId.value()).stream()
                .map(TreasuryWithdrawalApprovalRegistry::toApproval)
                .toList();
    }

    public List<TreasuryWithdrawalApproval> approvedWithoutOperation(
            ServiceIdentity serviceIdentity) {
        if (serviceIdentity == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal recovery Service Identity cannot be null");
        }
        return database.approvedTreasuryWithdrawalApprovalsWithoutOperation(
                        serviceIdentity.value())
                .stream()
                .map(TreasuryWithdrawalApprovalRegistry::toApproval)
                .toList();
    }

    public List<TreasuryWithdrawalApproval> expirePending() {
        long now = clock.millis();
        return database.expirePendingTreasuryWithdrawalApprovals(
                        TreasuryWithdrawalFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                        now,
                        now)
                .stream()
                .map(TreasuryWithdrawalApprovalRegistry::toApproval)
                .toList();
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
                        .map(vote -> new TreasuryWithdrawalApprovalVote(
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
