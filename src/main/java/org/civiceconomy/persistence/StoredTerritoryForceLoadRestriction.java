package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryForceLoadRestriction(
        UUID assessmentId,
        UUID nationId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long restrictedAtEpochMillis) {}
