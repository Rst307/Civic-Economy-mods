package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record PrepareTerritoryMaintenanceRestoration(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        UUID policyId,
        Instant nextFullCycleStartsAt,
        TerritoryMaintenanceRestorationQuote quote,
        String reason) {
    public PrepareTerritoryMaintenanceRestoration {
        if (serviceIdentity == null
                || nationId == null
                || ftbTeamId == null
                || actorPlayerId == null
                || policyId == null
                || nextFullCycleStartsAt == null
                || quote == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration preparation cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || dimensionId == null
                || dimensionId.isBlank()
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration preparation values are invalid");
        }
    }
}
