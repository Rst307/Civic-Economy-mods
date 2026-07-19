package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredAuditableEconomicActivityPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long observationWindowMillis,
        long fullStrengthScaleMinorUnits,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
