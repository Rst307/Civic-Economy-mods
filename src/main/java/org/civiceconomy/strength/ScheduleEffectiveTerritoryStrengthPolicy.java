package org.civiceconomy.strength;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleEffectiveTerritoryStrengthPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        EffectiveTerritoryStrengthPolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleEffectiveTerritoryStrengthPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Effective Territory Strength policy request is invalid");
        }
    }
}
