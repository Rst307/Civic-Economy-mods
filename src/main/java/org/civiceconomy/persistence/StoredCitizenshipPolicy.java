package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredCitizenshipPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long correctionGraceMillis,
        long transferCooldownMillis,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
