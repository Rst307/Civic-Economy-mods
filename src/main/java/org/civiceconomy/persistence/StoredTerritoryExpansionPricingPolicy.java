package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryExpansionPricingPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long firstOverageChunkCost,
        long additionalMarginalCost,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
