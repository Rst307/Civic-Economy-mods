package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryClaimPermitConsumption(
        UUID permitId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long consumedAtEpochMillis) {}
