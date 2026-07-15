package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record TerritoryForceLoadRestriction(
        UUID assessmentId,
        NationId nationId,
        UUID ftbTeamId,
        TerritoryClaimPosition position,
        Instant restrictedAt) {
    public TerritoryForceLoadRestriction {
        if (assessmentId == null
                || nationId == null
                || ftbTeamId == null
                || position == null
                || restrictedAt == null) {
            throw new IllegalArgumentException(
                    "Territory Force-load Restriction cannot contain null values");
        }
    }
}
