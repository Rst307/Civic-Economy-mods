package org.civiceconomy.mint;

import java.time.Clock;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.territory.EffectiveTerritoryQuery;

public final class EffectiveTerritoryMintAuthority implements RegisteredMintTerritoryAuthority {
    private final CivicDatabase database;
    private final EffectiveTerritoryQuery territory;
    private final Clock clock;

    public EffectiveTerritoryMintAuthority(
            CivicDatabase database, EffectiveTerritoryQuery territory, Clock clock) {
        if (database == null || territory == null || clock == null) {
            throw new IllegalArgumentException("Registered Mint territory dependencies cannot be null");
        }
        this.database = database;
        this.territory = territory;
        this.clock = clock;
    }

    @Override
    public boolean isEffective(RegisteredMint mint) {
        if (mint == null) {
            return false;
        }
        var cycle = database.territoryMaintenanceCycleAt(clock.millis());
        return cycle != null
                && territory.isEffective(
                        cycle.cycleId(),
                        mint.nationId(),
                        mint.dimensionId(),
                        Math.floorDiv(mint.blockX(), 16),
                        Math.floorDiv(mint.blockZ(), 16));
    }
}
