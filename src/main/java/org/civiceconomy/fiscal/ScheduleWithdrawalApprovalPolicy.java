package org.civiceconomy.fiscal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record ScheduleWithdrawalApprovalPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID actorPlayerId,
        List<WithdrawalApprovalTier> tiers,
        Duration approvalLifetime,
        Instant effectiveAt,
        String reason) {
    public ScheduleWithdrawalApprovalPolicy {
        if (serviceIdentity == null
                || nationId == null
                || actorPlayerId == null
                || approvalLifetime == null
                || effectiveAt == null) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Withdrawal approval policy reason cannot be blank");
        }
        if (approvalLifetime.isNegative() || approvalLifetime.isZero()) {
            throw new IllegalArgumentException(
                    "Withdrawal approval lifetime must be positive");
        }
        tiers = WithdrawalApprovalPolicyVersion.validatedTiers(tiers);
    }
}
