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
        long restorationFeeMinorUnits,
        String restorationEligibility,
        Long restorationCooldownEndsAtEpochMillis,
        String priority) {}
