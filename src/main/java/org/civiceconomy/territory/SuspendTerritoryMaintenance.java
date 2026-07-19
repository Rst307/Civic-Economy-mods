package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record SuspendTerritoryMaintenance(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID cycleId,
        NationId nationId,
        String reason) {
    public SuspendTerritoryMaintenance {
        if (serviceIdentity == null || cycleId == null || nationId == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance suspension cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance suspension values are invalid");
        }
    }
}
