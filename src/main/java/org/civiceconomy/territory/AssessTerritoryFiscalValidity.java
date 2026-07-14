package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record AssessTerritoryFiscalValidity(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID cycleId,
        NationId nationId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long maintenanceDueMinorUnits,
        String reason) {
    public AssessTerritoryFiscalValidity {
        if (serviceIdentity == null
                || cycleId == null
                || nationId == null
                || ftbTeamId == null) {
            throw new IllegalArgumentException(
                    "Territory Fiscal Assessment cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || dimensionId == null
                || dimensionId.isBlank()
                || reason == null
                || reason.isBlank()
                || maintenanceDueMinorUnits < 0L) {
            throw new IllegalArgumentException("Territory Fiscal Assessment values are invalid");
        }
    }
}
