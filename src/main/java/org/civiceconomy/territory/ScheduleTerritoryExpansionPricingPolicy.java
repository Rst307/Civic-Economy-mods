package org.civiceconomy.territory;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleTerritoryExpansionPricingPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        long firstOverageChunkCost,
        long additionalMarginalCost,
        Instant effectiveAt,
        String reason) {
    public ScheduleTerritoryExpansionPricingPolicy {
        if (serviceIdentity == null || effectiveAt == null) {
            throw new IllegalArgumentException("Territory Expansion pricing request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Territory Expansion pricing request ID cannot be blank");
        }
        if (actorIdentity == null || actorIdentity.isBlank()) {
            throw new IllegalArgumentException("Territory Expansion pricing actor cannot be blank");
        }
        if (firstOverageChunkCost < 0L || additionalMarginalCost < 0L) {
            throw new IllegalArgumentException("Territory Expansion pricing cannot be negative");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Territory Expansion pricing reason cannot be blank");
        }
    }
}
