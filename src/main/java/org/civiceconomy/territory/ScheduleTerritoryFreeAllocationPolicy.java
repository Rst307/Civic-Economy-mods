package org.civiceconomy.territory;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleTerritoryFreeAllocationPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        int baseChunks,
        int chunksPerEffectiveCitizen,
        Instant effectiveAt,
        String reason) {
    public ScheduleTerritoryFreeAllocationPolicy {
        if (serviceIdentity == null || effectiveAt == null) {
            throw new IllegalArgumentException("Territory Free Allocation policy request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Territory Free Allocation policy request ID cannot be blank");
        }
        if (actorIdentity == null || actorIdentity.isBlank()) {
            throw new IllegalArgumentException("Territory Free Allocation policy actor cannot be blank");
        }
        if (baseChunks < 0 || chunksPerEffectiveCitizen < 0) {
            throw new IllegalArgumentException("Territory Free Allocation policy values cannot be negative");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Territory Free Allocation policy reason cannot be blank");
        }
    }
}
