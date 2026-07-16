package org.civiceconomy.fiscal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record ScheduleBudgetDisbursementApprovalPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID actorPlayerId,
        List<BudgetDisbursementApprovalTier> tiers,
        Duration approvalLifetime,
        Instant effectiveAt,
        String reason) {
    public ScheduleBudgetDisbursementApprovalPolicy {
        if (serviceIdentity == null
                || nationId == null
                || actorPlayerId == null
                || approvalLifetime == null
                || effectiveAt == null) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval policy request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank()
                || approvalLifetime.isNegative() || approvalLifetime.isZero()) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval policy request is invalid");
        }
        tiers = BudgetDisbursementApprovalPolicyVersion.validatedTiers(tiers);
    }
}
