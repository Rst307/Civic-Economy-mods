package org.civiceconomy.fiscal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record BudgetDisbursementApprovalPolicyVersion(
        UUID policyId,
        NationId nationId,
        List<BudgetDisbursementApprovalTier> tiers,
        Duration approvalLifetime,
        Instant effectiveAt,
        UUID actorPlayerId,
        String reason,
        Instant recordedAt,
        boolean defaultPolicy) {
    private static final UUID DEFAULT_ID = new UUID(0L, 0L);

    public BudgetDisbursementApprovalPolicyVersion {
        if (policyId == null || nationId == null || approvalLifetime == null
                || effectiveAt == null || actorPlayerId == null || recordedAt == null) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval policy cannot contain null values");
        }
        if (reason == null || reason.isBlank()
                || approvalLifetime.isNegative() || approvalLifetime.isZero()) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval policy is invalid");
        }
        tiers = validatedTiers(tiers);
    }

    public int requiredApprovals(MoneyAmount amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Budget disbursement amount cannot be null");
        }
        int required = tiers.getFirst().requiredApprovals();
        for (BudgetDisbursementApprovalTier tier : tiers) {
            if (tier.minimumAmount().compareTo(amount) > 0) {
                break;
            }
            required = tier.requiredApprovals();
        }
        return required;
    }

    public static BudgetDisbursementApprovalPolicyVersion defaultPolicy(NationId nationId) {
        return new BudgetDisbursementApprovalPolicyVersion(
                DEFAULT_ID,
                nationId,
                List.of(new BudgetDisbursementApprovalTier(MoneyAmount.ZERO, 1)),
                Duration.ofDays(7L),
                Instant.EPOCH,
                DEFAULT_ID,
                "civiceconomy-default-single-budget-disbursement-approval",
                Instant.EPOCH,
                true);
    }

    static List<BudgetDisbursementApprovalTier> validatedTiers(
            List<BudgetDisbursementApprovalTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval policy requires at least one tier");
        }
        List<BudgetDisbursementApprovalTier> copy = List.copyOf(tiers);
        if (!copy.getFirst().minimumAmount().equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval policy must start at zero");
        }
        long previous = -1L;
        for (BudgetDisbursementApprovalTier tier : copy) {
            long minimum = tier.minimumAmount().minorUnits();
            if (minimum <= previous) {
                throw new IllegalArgumentException(
                        "Budget disbursement approval tiers must increase strictly");
            }
            previous = minimum;
        }
        return copy;
    }
}
