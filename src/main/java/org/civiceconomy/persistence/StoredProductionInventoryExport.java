package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredProductionInventoryExport(
        UUID exportId,
        String serviceIdentity,
        String requestId,
        UUID interfaceId,
        UUID actorPlayerId,
        int slot,
        String itemId,
        String componentFingerprint,
        int exportedCount,
        String kind,
        String destinationReference,
        long exportedAtEpochMillis,
        String reason,
        UUID consumptionId) {}
