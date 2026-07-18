package org.civiceconomy.production;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.territory.TerritoryClaimPosition;

public final class FacilityAdministration {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-facility");

    private final NationRegistry nations;
    private final NationProvider provider;
    private final NationFiscalAuthorityRegistry authorities;
    private final RegisteredFacilityRegistry facilities;
    private final FacilityAccountingInterfaceRegistry interfaces;

    public FacilityAdministration(
            NationRegistry nations,
            NationProvider provider,
            NationFiscalAuthorityRegistry authorities,
            RegisteredFacilityRegistry facilities,
            FacilityAccountingInterfaceRegistry interfaces) {
        if (nations == null || provider == null || authorities == null || facilities == null
                || interfaces == null) {
            throw new IllegalArgumentException(
                    "Facility Administration dependencies cannot be null");
        }
        this.nations = nations;
        this.provider = provider;
        this.authorities = authorities;
        this.facilities = facilities;
        this.interfaces = interfaces;
    }

    public RegisteredFacility register(
            UUID actorPlayerId,
            UUID ftbTeamId,
            String requestId,
            FacilityCorePosition core,
            List<TerritoryClaimPosition> scope,
            String reason) {
        if (actorPlayerId == null || ftbTeamId == null) {
            throw new IllegalArgumentException(
                    "Facility Administration actor and FTB Team are required");
        }
        NationId boundNationId = requireAuthority(actorPlayerId, ftbTeamId);
        return facilities.register(new RegisterFacility(
                SERVICE_IDENTITY,
                requestId,
                facilityId(requestId),
                        boundNationId,
                ftbTeamId,
                core,
                scope,
                actorPlayerId,
                reason));
    }

    public FacilityAccountingInterface bindInterface(
            UUID actorPlayerId,
            UUID ftbTeamId,
            String requestId,
            FacilityAccountingInterfacePosition position,
            String reason) {
        if (actorPlayerId == null || ftbTeamId == null || position == null) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface actor, FTB Team, and position are required");
        }
        NationId boundNationId = requireAuthority(actorPlayerId, ftbTeamId);
        RegisteredFacility facility = facilities.facilityAt(position);
        if (facility == null
                || !facility.nationId().equals(boundNationId)
                || !facility.ftbTeamId().equals(ftbTeamId)) {
            throw new SecurityException(
                    "Facility Accounting Interface is not inside the actor's exact-Nation Facility");
        }
        return interfaces.register(new RegisterFacilityAccountingInterface(
                SERVICE_IDENTITY,
                requestId,
                interfaceId(requestId),
                facility.facilityId(),
                position,
                actorPlayerId,
                reason));
    }

    public NationId requireAuthority(UUID actorPlayerId, UUID ftbTeamId) {
        NationFacts actorNation = provider.findForCitizen(actorPlayerId)
                .orElseThrow(() -> new SecurityException(
                        "Facility actor has no effective formal Citizenship"));
        var boundNation = nations.findByFtbTeam(ftbTeamId)
                .orElseThrow(() -> new SecurityException(
                        "Facility FTB Team is not bound to a formal Nation"));
        if (!actorNation.nationId().equals(boundNation.nationId())) {
            throw new SecurityException(
                    "Facility actor does not belong to the exact FTB-bound Nation");
        }
        authorities.require(
                boundNation.nationId(),
                actorPlayerId,
                NationFiscalPermission.MANAGE_FACILITY_ACCOUNTING);
        return boundNation.nationId();
    }

    private static UUID facilityId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Facility request ID is required");
        }
        return UUID.nameUUIDFromBytes(
                (SERVICE_IDENTITY.value() + ":" + requestId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static UUID interfaceId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface request ID is required");
        }
        return UUID.nameUUIDFromBytes(
                (SERVICE_IDENTITY.value() + ":interface:" + requestId)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
