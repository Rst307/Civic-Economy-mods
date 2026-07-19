package org.civiceconomy.nation;

import java.time.Duration;

public record CandidateOnlineEvidencePolicy(Duration observationWindow) {
    public CandidateOnlineEvidencePolicy {
        if (observationWindow == null
                || observationWindow.isZero()
                || observationWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "Candidate Online Evidence observation window must be positive");
        }
    }
}
