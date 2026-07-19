package org.civiceconomy.fiscal;

import java.time.Instant;

public record CreateBudget(
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId sourceAccount,
        MoneyAmount amount,
        String budgetCode,
        String purpose,
        Instant expiresAt) {
    public CreateBudget {
        if (serviceIdentity == null || sourceAccount == null || amount == null || expiresAt == null) {
            throw new IllegalArgumentException("Budget identity, source, amount, and expiry cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Budget amount must be positive");
        }
        if (budgetCode == null || budgetCode.isBlank()) {
            throw new IllegalArgumentException("Budget code cannot be blank");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Budget purpose cannot be blank");
        }
    }
}
