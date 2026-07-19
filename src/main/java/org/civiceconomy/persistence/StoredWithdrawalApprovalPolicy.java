package org.civiceconomy.persistence;

import java.util.List;
import java.util.UUID;

public record StoredWithdrawalApprovalPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID actorPlayerId,
        List<StoredWithdrawalApprovalTier> tiers,
        long approvalLifetimeMillis,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {
    public StoredWithdrawalApprovalPolicy {
        tiers = List.copyOf(tiers);
        if (approvalLifetimeMillis <= 0L) {
            throw new IllegalArgumentException(
                    "Withdrawal approval lifetime must be positive");
        }
    }
}
