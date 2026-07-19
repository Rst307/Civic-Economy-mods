package org.civiceconomy.production;

import java.time.Clock;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.territory.EffectiveTerritoryQuery;
import org.civiceconomy.territory.TerritoryClaimPosition;

public final class EffectiveTerritoryFacilityAuthority
        implements RegisteredFacilityTerritoryAuthority {
    private final CivicDatabase database;
    private final EffectiveTerritoryQuery territory;
    private final Clock clock;

    public EffectiveTerritoryFacilityAuthority(
            CivicDatabase database, EffectiveTerritoryQuery territory, Clock clock) {
        if (database == null || territory == null || clock == null) {
            throw new IllegalArgumentException(
                    "Registered Facility territory dependencies cannot be null");
        }
        this.database = database;
        this.territory = territory;
        this.clock = clock;
    }

    @Override
    public boolean isEffective(
            NationId nationId, UUID ftbTeamId, TerritoryClaimPosition claim) {
        if (nationId == null || ftbTeamId == null || claim == null) {
            return false;
        }
        var cycle = database.territoryMaintenanceCycleAt(clock.millis());
        return cycle != null && territory.isEffective(
                cycle.cycleId(),
                nationId,
                ftbTeamId,
                claim.dimensionId(),
                claim.chunkX(),
                claim.chunkZ());
    }
}
