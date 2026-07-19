package org.civiceconomy.production;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleRegisteredFacilityScopePolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        RegisteredFacilityScopePolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleRegisteredFacilityScopePolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Registered Facility Scope policy request is invalid");
        }
    }
}
