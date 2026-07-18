package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredProductionInventoryAgeBatch(
        UUID batchId,
        UUID receiptId,
        UUID interfaceId,
        int slot,
        String itemId,
        String componentFingerprint,
        int originalCount,
        int remainingCount,
        long firstObservedAtEpochMillis) {}
