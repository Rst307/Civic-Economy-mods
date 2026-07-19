package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredOnlineTimeObservationPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long checkpointIntervalMillis,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
