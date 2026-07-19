package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationApplicationTransition(
        UUID transitionId,
        UUID applicationId,
        String serviceIdentity,
        String requestId,
        UUID actorPlayerId,
        Long observationWindowMillis,
        String toState,
        String reason,
        long effectiveAtEpochMillis,
        long transitionedAtEpochMillis) {}
