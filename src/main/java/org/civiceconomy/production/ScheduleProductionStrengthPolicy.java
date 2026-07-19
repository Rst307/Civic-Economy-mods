package org.civiceconomy.production;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleProductionStrengthPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        ProductionStrengthPolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleProductionStrengthPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Production Strength policy request is invalid");
        }
    }
}
