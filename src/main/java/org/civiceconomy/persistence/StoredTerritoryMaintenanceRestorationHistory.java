package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryMaintenanceRestorationHistory(
        UUID nationId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        String previousValidity,
        Long lastSuccessfulRestorationCooldownEndsAtEpochMillis) {}
