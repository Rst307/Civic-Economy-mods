package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryFreeAllocationPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        int baseChunks,
        int chunksPerEffectiveCitizen,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
