package org.civiceconomy.fiscal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record WithdrawalApprovalPolicyVersion(
        UUID policyId,
        NationId nationId,
        List<WithdrawalApprovalTier> tiers,
        Duration approvalLifetime,
        Instant effectiveAt,
        UUID actorPlayerId,
        String reason,
        Instant recordedAt,
        boolean defaultPolicy) {
    private static final UUID DEFAULT_POLICY_ID = new UUID(0L, 0L);
    private static final UUID DEFAULT_ACTOR_ID = new UUID(0L, 0L);

    public WithdrawalApprovalPolicyVersion {
        if (policyId == null
                || nationId == null
                || approvalLifetime == null
                || effectiveAt == null
                || actorPlayerId == null
                || recordedAt == null) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy cannot contain null values");
        }
        if (approvalLifetime.isNegative() || approvalLifetime.isZero()) {
            throw new IllegalArgumentException(
                    "Withdrawal approval lifetime must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Withdrawal approval policy reason cannot be blank");
        }
        tiers = validatedTiers(tiers);
    }

    public int requiredApprovals(MoneyAmount amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Withdrawal approval amount cannot be null");
        }
        int required = tiers.getFirst().requiredApprovals();
        for (WithdrawalApprovalTier tier : tiers) {
            if (tier.minimumAmount().minorUnits() > amount.minorUnits()) {
                break;
            }
            required = tier.requiredApprovals();
        }
        return required;
    }

    public static WithdrawalApprovalPolicyVersion defaultPolicy(NationId nationId) {
        return new WithdrawalApprovalPolicyVersion(
                DEFAULT_POLICY_ID,
                nationId,
                List.of(new WithdrawalApprovalTier(MoneyAmount.ZERO, 1)),
                Duration.ofDays(7L),
                Instant.EPOCH,
                DEFAULT_ACTOR_ID,
                "civiceconomy-default-single-approval",
                Instant.EPOCH,
                true);
    }

    static List<WithdrawalApprovalTier> validatedTiers(
            List<WithdrawalApprovalTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy requires at least one tier");
        }
        List<WithdrawalApprovalTier> copy = List.copyOf(tiers);
        if (!copy.getFirst().minimumAmount().equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy must start at zero");
        }
        long previous = -1L;
        for (WithdrawalApprovalTier tier : copy) {
            long minimum = tier.minimumAmount().minorUnits();
            if (minimum <= previous) {
                throw new IllegalArgumentException(
                        "Withdrawal approval tiers must have strictly increasing amounts");
            }
            previous = minimum;
        }
        return copy;
    }
}
