package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;

public record TerritoryMaintenanceCandidate(
        UUID assessmentId,
        TerritoryMaintenancePriority priority,
        String dimensionId,
        int chunkX,
        int chunkZ,
        MoneyAmount maintenanceDue) {
    public TerritoryMaintenanceCandidate {
        if (assessmentId == null || priority == null || maintenanceDue == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance candidate cannot contain null values");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance candidate dimension cannot be blank");
        }
    }
}
