package org.civiceconomy.strength;

import java.time.Instant;
import java.util.UUID;

public record EffectiveTerritoryStrengthPolicyVersion(
        UUID policyId,
        EffectiveTerritoryStrengthPolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public EffectiveTerritoryStrengthPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Effective Territory Strength policy version is invalid");
        }
    }
}
