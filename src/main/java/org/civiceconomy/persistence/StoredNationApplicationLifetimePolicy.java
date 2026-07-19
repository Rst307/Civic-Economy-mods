package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationApplicationLifetimePolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long lifetimeMillis,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
