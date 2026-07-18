package org.civiceconomy.strength;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleEffectiveCitizenStrengthPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        EffectiveCitizenStrengthPolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleEffectiveCitizenStrengthPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Effective Citizen Strength policy request is invalid");
        }
    }
}
