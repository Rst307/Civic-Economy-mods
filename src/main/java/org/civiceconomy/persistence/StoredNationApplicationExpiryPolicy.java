package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationApplicationExpiryPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long scanIntervalMillis,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
