package org.civiceconomy.nation;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleNationFoundingCandidateThresholdPolicy(
        ServiceIdentity serviceIdentity, String requestId, String actorIdentity,
        NationFoundingCandidateThresholdPolicy policy, Instant effectiveAt, String reason) {
    public ScheduleNationFoundingCandidateThresholdPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Formal founding candidate threshold policy request is invalid");
        }
    }
}
