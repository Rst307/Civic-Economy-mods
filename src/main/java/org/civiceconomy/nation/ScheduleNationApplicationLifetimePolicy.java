package org.civiceconomy.nation;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleNationApplicationLifetimePolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        NationApplicationLifetimePolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleNationApplicationLifetimePolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Nation Application lifetime policy request is invalid");
        }
    }
}
