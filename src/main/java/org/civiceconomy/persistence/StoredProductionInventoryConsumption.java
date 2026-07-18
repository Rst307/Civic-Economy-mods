package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredProductionInventoryConsumption(
        UUID consumptionId,
        String serviceIdentity,
        String requestId,
        UUID interfaceId,
        String itemId,
        String componentFingerprint,
        int consumedCount,
        long consumedAtEpochMillis,
        String reason) {}
