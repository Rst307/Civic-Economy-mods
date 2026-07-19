package org.civiceconomy.nation;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleNationApplicationExpiryPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        NationApplicationExpiryPolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleNationApplicationExpiryPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Nation Application expiry policy request is invalid");
        }
    }
}
