package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredEffectiveCitizenPopulationPolicy(
        UUID policyId, String serviceIdentity, String requestId, String actorIdentity,
        long observationWindowMillis, long fullContributionTimeMillis,
        long effectiveAtEpochMillis, String reason, long recordedAtEpochMillis) {}
