package org.civiceconomy.strength;

import java.time.Instant;
import java.util.UUID;

public record EffectiveCitizenStrengthPolicyVersion(
        UUID policyId,
        EffectiveCitizenStrengthPolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public EffectiveCitizenStrengthPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Effective Citizen Strength policy version is invalid");
        }
    }
}
