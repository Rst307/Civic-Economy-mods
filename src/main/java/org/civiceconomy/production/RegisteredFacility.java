package org.civiceconomy.production;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.territory.TerritoryClaimPosition;

public record RegisteredFacility(
        UUID facilityId,
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID ftbTeamId,
        FacilityCorePosition core,
        Set<TerritoryClaimPosition> scope,
        UUID actorPlayerId,
        RegisteredFacilityState state,
        String reason,
        Instant registeredAt) {
    public RegisteredFacility {
        scope = Set.copyOf(scope);
    }
}
