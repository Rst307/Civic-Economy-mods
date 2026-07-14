package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record ConsumeTerritoryClaimPermit(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID permitId,
        NationId nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ) {
    public ConsumeTerritoryClaimPermit {
        if (serviceIdentity == null
                || permitId == null
                || nationId == null
                || ftbTeamId == null
                || actorPlayerId == null) {
            throw new IllegalArgumentException("Territory Claim Permit consumption cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim Permit consumption request ID cannot be blank");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim Permit consumption dimension cannot be blank");
        }
    }
}
