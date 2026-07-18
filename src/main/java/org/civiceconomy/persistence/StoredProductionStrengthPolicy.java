package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredProductionStrengthPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long observationWindowMillis,
        long fullWeightWindowMillis,
        long fullStrengthScaleMinorUnits,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
