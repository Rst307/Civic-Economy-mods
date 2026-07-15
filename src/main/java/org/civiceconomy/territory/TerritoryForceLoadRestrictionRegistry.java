package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;

public final class TerritoryForceLoadRestrictionRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public TerritoryForceLoadRestrictionRegistry(CivicDatabase database) {
        this(database, Clock.systemUTC());
    }

    public TerritoryForceLoadRestrictionRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Territory Force-load Restriction dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public List<TerritoryForceLoadRestriction> active() {
        return database.activeTerritoryForceLoadRestrictions(clock.millis()).stream()
                .map(stored -> new TerritoryForceLoadRestriction(
                        stored.assessmentId(),
                        new NationId(stored.nationId()),
                        stored.ftbTeamId(),
                        new TerritoryClaimPosition(
                                stored.dimensionId(), stored.chunkX(), stored.chunkZ()),
                        Instant.ofEpochMilli(stored.restrictedAtEpochMillis())))
                .toList();
    }
}
