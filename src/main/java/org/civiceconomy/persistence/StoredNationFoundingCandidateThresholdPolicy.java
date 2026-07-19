package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationFoundingCandidateThresholdPolicy(
        UUID policyId, String serviceIdentity, String requestId, String actorIdentity,
        int minimumEffectiveCandidates, long effectiveAtEpochMillis, String reason,
        long recordedAtEpochMillis) {}
