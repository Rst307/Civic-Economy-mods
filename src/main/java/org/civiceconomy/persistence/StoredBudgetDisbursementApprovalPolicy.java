package org.civiceconomy.persistence;

import java.util.List;
import java.util.UUID;

public record StoredBudgetDisbursementApprovalPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID actorPlayerId,
        List<StoredBudgetDisbursementApprovalTier> tiers,
        long approvalLifetimeMillis,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
