package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredBudgetCancellationAudit(
        UUID cancellationId,
        UUID budgetId,
        String serviceIdentity,
        String requestId,
        UUID actorPlayerId,
        String reason,
        long cancelledAtEpochMillis) {}
