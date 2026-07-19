package org.civiceconomy.strength;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleAuditableEconomicActivityPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        AuditableEconomicActivityPolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleAuditableEconomicActivityPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity policy request is invalid");
        }
    }
}
