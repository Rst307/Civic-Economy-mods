package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record Budget(
        UUID budgetId,
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId sourceAccount,
        MoneyAmount amount,
        String budgetCode,
        String purpose,
        Instant expiresAt,
        Optional<UUID> escrowId,
        MoneyAmount settledAmount,
        BudgetState state) {
    public MoneyAmount remainingAmount() {
        return amount.minus(settledAmount);
    }
}
