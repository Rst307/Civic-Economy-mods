package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredBudget(
        UUID budgetId,
        String serviceIdentity,
        String requestId,
        String sourceAccount,
        long amountMinorUnits,
        String budgetCode,
        String purpose,
        long expiresAtEpochMillis,
        UUID escrowId,
        long settledMinorUnits,
        String state) {}
