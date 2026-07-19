package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredEffectiveTerritoryStrengthPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        int fullStrengthScaleEffectiveClaims,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
