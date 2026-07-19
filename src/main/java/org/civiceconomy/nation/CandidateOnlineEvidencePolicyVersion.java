package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record CandidateOnlineEvidencePolicyVersion(
        UUID policyId,
        CandidateOnlineEvidencePolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public CandidateOnlineEvidencePolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Candidate Online Evidence policy version is invalid");
        }
    }
}
