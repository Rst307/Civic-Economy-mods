package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredBudgetApprovalAudit(
        UUID approvalId,
        UUID budgetId,
        String serviceIdentity,
        String requestId,
        UUID actorPlayerId,
        String reason,
        long approvedAtEpochMillis) {}
