package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.territory.TerritoryClaimPosition;

@FunctionalInterface
public interface RegisteredFacilityTerritoryAuthority {
    boolean isEffective(
            NationId nationId, UUID ftbTeamId, TerritoryClaimPosition claim);
}
