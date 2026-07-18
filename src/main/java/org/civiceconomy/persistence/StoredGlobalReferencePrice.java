package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredGlobalReferencePrice(
        UUID priceId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        String itemId,
        String componentFingerprint,
        long unitPriceMinorUnits,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
