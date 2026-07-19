package org.civiceconomy.production;

import java.time.Instant;
import java.util.UUID;

public record RegisteredFacilityScopePolicyVersion(
        UUID policyId,
        RegisteredFacilityScopePolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public RegisteredFacilityScopePolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Registered Facility Scope policy version is invalid");
        }
    }
}
