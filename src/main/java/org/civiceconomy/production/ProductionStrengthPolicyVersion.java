package org.civiceconomy.production;

import java.time.Instant;
import java.util.UUID;

public record ProductionStrengthPolicyVersion(
        UUID policyId,
        ProductionStrengthPolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public ProductionStrengthPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Production Strength policy version is invalid");
        }
    }
}
