package org.civiceconomy.production;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleProductionMarginalReturnPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        ProductionMarginalReturnPolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleProductionMarginalReturnPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null) {
            throw new IllegalArgumentException(
                    "Production Marginal Return policy request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Production Marginal Return policy request is invalid");
        }
    }
}
