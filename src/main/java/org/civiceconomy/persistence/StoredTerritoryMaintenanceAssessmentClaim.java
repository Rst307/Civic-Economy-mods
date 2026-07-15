package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryMaintenanceAssessmentClaim(
        UUID cycleId,
        int ordinal,
        UUID nationId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long maintenanceDueMinorUnits,
        String priority) {}
