package org.civiceconomy.fiscal;

import java.util.UUID;

public record CancelBudget(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID budgetId,
        UUID actorPlayerId,
        String reason) {
    public CancelBudget {
        if (serviceIdentity == null || budgetId == null || actorPlayerId == null) {
            throw new IllegalArgumentException("Budget cancellation identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Budget cancellation request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Budget cancellation reason cannot be blank");
        }
    }
}
