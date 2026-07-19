package org.civiceconomy.nation;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleOnlineTimeObservationPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        OnlineTimeObservationPolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleOnlineTimeObservationPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Online Time Observation policy request is invalid");
        }
    }
}
