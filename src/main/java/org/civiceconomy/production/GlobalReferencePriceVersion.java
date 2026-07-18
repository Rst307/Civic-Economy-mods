package org.civiceconomy.production;

import java.time.Instant;
import java.util.UUID;

public record GlobalReferencePriceVersion(
        UUID priceId,
        String itemId,
        String componentFingerprint,
        long unitPriceMinorUnits,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public GlobalReferencePriceVersion {
        if (priceId == null
                || itemId == null
                || componentFingerprint == null
                || effectiveAt == null
                || actorIdentity == null
                || reason == null
                || recordedAt == null) {
            throw new IllegalArgumentException("Global Reference Price cannot contain null values");
        }
        if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || componentFingerprint.isBlank()
                || unitPriceMinorUnits <= 0L
                || actorIdentity.isBlank()
                || reason.isBlank()) {
            throw new IllegalArgumentException("Global Reference Price is invalid");
        }
    }
}
