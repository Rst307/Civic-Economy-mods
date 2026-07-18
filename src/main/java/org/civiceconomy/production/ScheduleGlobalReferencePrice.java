package org.civiceconomy.production;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleGlobalReferencePrice(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        String itemId,
        String componentFingerprint,
        long unitPriceMinorUnits,
        Instant effectiveAt,
        String reason) {
    public ScheduleGlobalReferencePrice {
        if (serviceIdentity == null || effectiveAt == null) {
            throw new IllegalArgumentException("Global Reference Price request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Global Reference Price request ID cannot be blank");
        }
        if (actorIdentity == null || actorIdentity.isBlank()) {
            throw new IllegalArgumentException("Global Reference Price actor cannot be blank");
        }
        if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Global Reference Price item ID is invalid");
        }
        if (componentFingerprint == null || componentFingerprint.isBlank()) {
            throw new IllegalArgumentException("Global Reference Price component identity cannot be blank");
        }
        if (unitPriceMinorUnits <= 0L) {
            throw new IllegalArgumentException("Global Reference Price must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Global Reference Price reason cannot be blank");
        }
    }
}
