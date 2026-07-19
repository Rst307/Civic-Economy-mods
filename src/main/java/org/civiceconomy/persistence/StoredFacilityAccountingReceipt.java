package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFacilityAccountingReceipt(
        UUID receiptId,
        UUID interfaceId,
        String dimensionId,
        int blockX,
        int blockY,
        int blockZ,
        long observedAtEpochMillis) {}
