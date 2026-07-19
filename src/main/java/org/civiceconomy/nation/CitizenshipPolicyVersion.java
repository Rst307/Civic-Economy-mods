package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record CitizenshipPolicyVersion(
        UUID policyId,
        CitizenshipPolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public CitizenshipPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Citizenship policy version is invalid");
        }
    }
}
