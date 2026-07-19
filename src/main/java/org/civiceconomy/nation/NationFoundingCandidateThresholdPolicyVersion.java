package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record NationFoundingCandidateThresholdPolicyVersion(
        UUID policyId, NationFoundingCandidateThresholdPolicy policy, Instant effectiveAt,
        String actorIdentity, String reason, Instant recordedAt) {
    public NationFoundingCandidateThresholdPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Formal founding candidate threshold policy version is invalid");
        }
    }
}
