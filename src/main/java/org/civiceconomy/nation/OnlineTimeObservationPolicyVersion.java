package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record OnlineTimeObservationPolicyVersion(
        UUID policyId,
        OnlineTimeObservationPolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public OnlineTimeObservationPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Online Time Observation policy version is invalid");
        }
    }
}
