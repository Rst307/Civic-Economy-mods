package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record TerritoryClaimTarget(
        NationId nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ) {
    public TerritoryClaimTarget {
        if (nationId == null || ftbTeamId == null || actorPlayerId == null) {
            throw new IllegalArgumentException("Territory Claim target cannot contain null values");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim target dimension cannot be blank");
        }
    }
}
