package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredEffectiveCitizenStrengthPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        int fullStrengthScaleCitizenEquivalents,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
