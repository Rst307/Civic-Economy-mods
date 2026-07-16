package org.civiceconomy.fiscal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredWithdrawalApprovalPolicy;
import org.civiceconomy.persistence.StoredWithdrawalApprovalTier;

public final class WithdrawalApprovalPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public WithdrawalApprovalPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public WithdrawalApprovalPolicyVersion schedule(
            ScheduleWithdrawalApprovalPolicy request) {
        StoredWithdrawalApprovalPolicy replay = database.withdrawalApprovalPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toPolicy(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy must take effect in the future");
        }
        return toPolicy(database.scheduleWithdrawalApprovalPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.actorPlayerId(),
                request.tiers().stream()
                        .map(tier -> new StoredWithdrawalApprovalTier(
                                tier.minimumAmount().minorUnits(),
                                tier.requiredApprovals()))
                        .toList(),
                request.approvalLifetime().toMillis(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public WithdrawalApprovalPolicyVersion current(NationId nationId, Instant asOf) {
        if (nationId == null || asOf == null) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy lookup cannot contain null values");
        }
        StoredWithdrawalApprovalPolicy stored = database.currentWithdrawalApprovalPolicy(
                nationId.value(), asOf.toEpochMilli());
        return stored == null
                ? WithdrawalApprovalPolicyVersion.defaultPolicy(nationId)
                : toPolicy(stored);
    }

    private static void requirePayload(
            StoredWithdrawalApprovalPolicy stored,
            ScheduleWithdrawalApprovalPolicy request) {
        var tiers = request.tiers().stream()
                .map(tier -> new StoredWithdrawalApprovalTier(
                        tier.minimumAmount().minorUnits(), tier.requiredApprovals()))
                .toList();
        if (!stored.nationId().equals(request.nationId().value())
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || !stored.tiers().equals(tiers)
                || stored.approvalLifetimeMillis()
                        != request.approvalLifetime().toMillis()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static WithdrawalApprovalPolicyVersion toPolicy(
            StoredWithdrawalApprovalPolicy stored) {
        return new WithdrawalApprovalPolicyVersion(
                stored.policyId(),
                new NationId(stored.nationId()),
                stored.tiers().stream()
                        .map(tier -> new WithdrawalApprovalTier(
                                MoneyAmount.ofMinorUnits(tier.minimumAmountMinorUnits()),
                                tier.requiredApprovals()))
                        .toList(),
                java.time.Duration.ofMillis(stored.approvalLifetimeMillis()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorPlayerId(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()),
                false);
    }
}
