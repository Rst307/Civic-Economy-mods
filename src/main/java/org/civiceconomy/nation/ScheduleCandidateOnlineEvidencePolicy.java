package org.civiceconomy.nation;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleCandidateOnlineEvidencePolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        CandidateOnlineEvidencePolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleCandidateOnlineEvidencePolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Candidate Online Evidence policy request is invalid");
        }
    }
}
