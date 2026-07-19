package org.civiceconomy.nation;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleCitizenshipPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        CitizenshipPolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleCitizenshipPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Citizenship policy request is invalid");
        }
    }
}
