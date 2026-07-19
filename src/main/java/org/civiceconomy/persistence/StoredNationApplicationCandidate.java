package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationApplicationCandidate(
        UUID applicationId,
        UUID playerId,
        long affiliatedAtEpochMillis,
        Long endedAtEpochMillis) {}
