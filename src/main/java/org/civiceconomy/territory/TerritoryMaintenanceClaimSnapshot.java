package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record TerritoryMaintenanceClaimSnapshot(
        NationId nationId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long maintenanceDueMinorUnits,
        TerritoryMaintenancePriority priority) {
    public TerritoryMaintenanceClaimSnapshot {
        if (nationId == null || ftbTeamId == null || priority == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Claim Snapshot cannot contain null values");
        }
        if (dimensionId == null || dimensionId.isBlank() || maintenanceDueMinorUnits < 0L) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Claim Snapshot values are invalid");
        }
    }
}
