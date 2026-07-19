package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record EffectiveCitizenPopulationPolicyVersion(
        UUID policyId, EffectiveCitizenPopulationPolicy policy, Instant effectiveAt,
        String actorIdentity, String reason, Instant recordedAt) {
    public EffectiveCitizenPopulationPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Effective Citizen population policy version is invalid");
        }
    }
}
