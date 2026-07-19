package org.civiceconomy.nation;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleEffectiveCitizenPopulationPolicy(
        ServiceIdentity serviceIdentity, String requestId, String actorIdentity,
        EffectiveCitizenPopulationPolicy policy, Instant effectiveAt, String reason) {
    public ScheduleEffectiveCitizenPopulationPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Effective Citizen population policy request is invalid");
        }
    }
}
