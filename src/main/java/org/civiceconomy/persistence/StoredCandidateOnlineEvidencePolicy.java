package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredCandidateOnlineEvidencePolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long observationWindowMillis,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
