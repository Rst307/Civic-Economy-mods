package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredBudgetDraftExpiry(
        UUID expiryId,
        UUID budgetId,
        String serviceIdentity,
        String requestId,
        long expiredAtEpochMillis) {}
