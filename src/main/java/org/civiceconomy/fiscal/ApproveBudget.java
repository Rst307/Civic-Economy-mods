package org.civiceconomy.fiscal;

import java.util.UUID;

public record ApproveBudget(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID budgetId) {
    public ApproveBudget {
        if (serviceIdentity == null || budgetId == null) {
            throw new IllegalArgumentException("Budget approval identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
    }
}
