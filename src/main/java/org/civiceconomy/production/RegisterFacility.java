package org.civiceconomy.production;

import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.territory.TerritoryClaimPosition;

public record RegisterFacility(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID facilityId,
        NationId nationId,
        UUID ftbTeamId,
        FacilityCorePosition core,
        List<TerritoryClaimPosition> scope,
        UUID actorPlayerId,
        String reason) {
    public RegisterFacility {
        if (serviceIdentity == null || facilityId == null || nationId == null
                || ftbTeamId == null || core == null || scope == null
                || actorPlayerId == null) {
            throw new IllegalArgumentException("Registered Facility request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Registered Facility request values are invalid");
        }
        scope = List.copyOf(scope);
    }
}
