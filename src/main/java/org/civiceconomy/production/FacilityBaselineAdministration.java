package org.civiceconomy.production;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.persistence.CivicDatabase;

public final class FacilityBaselineAdministration {
    private final CivicDatabase database;
    private final NationRegistry nations;
    private final NationProvider provider;
    private final NationFiscalAuthorityRegistry authorities;
    private final RegisteredFacilityRegistry facilities;
    private final FacilityAccountingInterfaceRegistry interfaces;
    private final RegisteredFacilityTerritoryAuthority territoryAuthority;
    private final Clock clock;

    public FacilityBaselineAdministration(
            CivicDatabase database,
            NationRegistry nations,
            NationProvider provider,
            NationFiscalAuthorityRegistry authorities,
            RegisteredFacilityRegistry facilities,
            FacilityAccountingInterfaceRegistry interfaces,
            RegisteredFacilityTerritoryAuthority territoryAuthority,
            Clock clock) {
        if (database == null || nations == null || provider == null || authorities == null
                || facilities == null || interfaces == null || territoryAuthority == null
                || clock == null) {
            throw new IllegalArgumentException(
                    "Facility Baseline Administration dependencies cannot be null");
        }
        this.database = database;
        this.nations = nations;
        this.provider = provider;
        this.authorities = authorities;
        this.facilities = facilities;
        this.interfaces = interfaces;
        this.territoryAuthority = territoryAuthority;
        this.clock = clock;
    }

    public FacilityBaselineCapturePreparation prepareCapture(
            UUID actorPlayerId,
            UUID ftbTeamId,
            String requestId,
            FacilityAccountingInterfacePosition currentPosition,
            String reason) {
        if (actorPlayerId == null || ftbTeamId == null || currentPosition == null) {
            throw new IllegalArgumentException(
                    "Facility Baseline actor, FTB Team, and position are required");
        }
        NationId nationId = requireAuthority(actorPlayerId, ftbTeamId);
        RegisteredFacility facility = facilities.facilityAt(currentPosition);
        requireExactFacility(facility, nationId, ftbTeamId);
        FacilityAccountingInterface accountingInterface =
                interfaces.interfaceFor(facility.facilityId());
        if (accountingInterface == null) {
            throw new IllegalStateException(
                    "Facility Accounting Baseline requires a registered interface");
        }
        CaptureFacilityAccountingBaseline request =
                new CaptureFacilityAccountingBaseline(
                        FacilityAdministration.SERVICE_IDENTITY,
                        requestId,
                        baselineId(requestId),
                        facility.facilityId(),
                        actorPlayerId,
                        reason);
        FacilityAccountingBaseline replay = registry(null).captureReplay(request);
        return replay == null
                ? new FacilityBaselineCaptureWork(request, facility, accountingInterface)
                : new FacilityBaselineCaptureReplay(replay);
    }

    public FacilityAccountingBaseline completeCapture(
            FacilityBaselineCaptureWork work,
            FacilityAccountingBaselineSnapshot snapshot) {
        if (work == null || snapshot == null) {
            throw new IllegalArgumentException(
                    "Facility Baseline capture work and snapshot are required");
        }
        CaptureFacilityAccountingBaseline request = work.request();
        if (!FacilityAdministration.SERVICE_IDENTITY.equals(request.serviceIdentity())
                || !baselineId(request.requestId()).equals(request.baselineId())) {
            throw new SecurityException(
                    "Facility Baseline capture work has an invalid trusted identity");
        }
        NationId nationId = requireAuthority(
                request.actorPlayerId(), work.facility().ftbTeamId());
        RegisteredFacility facility = facilities.facility(work.facility().facilityId());
        requireExactFacility(facility, nationId, work.facility().ftbTeamId());
        FacilityAccountingInterface accountingInterface =
                interfaces.interfaceFor(facility.facilityId());
        if (accountingInterface == null
                || !accountingInterface.interfaceId()
                        .equals(work.accountingInterface().interfaceId())
                || !accountingInterface.position()
                        .equals(work.accountingInterface().position())) {
            throw new SecurityException(
                    "Facility Accounting Interface changed before Baseline capture");
        }
        return registry((requestedFacility, requestedInterface) -> {
                    if (!requestedFacility.facilityId().equals(facility.facilityId())
                            || !requestedInterface.interfaceId()
                                    .equals(accountingInterface.interfaceId())) {
                        throw new SecurityException(
                                "Captured Facility Baseline snapshot target changed");
                    }
                    return snapshot;
                })
                .capture(request);
    }

    private NationId requireAuthority(UUID actorPlayerId, UUID ftbTeamId) {
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

    private static void requireExactFacility(
            RegisteredFacility facility, NationId nationId, UUID ftbTeamId) {
        if (facility == null
                || !facility.nationId().equals(nationId)
                || !facility.ftbTeamId().equals(ftbTeamId)) {
            throw new SecurityException(
                    "Facility position is not inside the actor's exact-Nation Facility");
        }
    }

    private FacilityAccountingBaselineRegistry registry(
            FacilityAccountingBaselineSnapshotSource snapshotSource) {
        FacilityAccountingBaselineSnapshotSource source = snapshotSource == null
                ? (facility, accountingInterface) -> {
                    throw new IllegalStateException(
                            "Facility Baseline snapshot was not captured on the server thread");
                }
                : snapshotSource;
        return new FacilityAccountingBaselineRegistry(
                database, territoryAuthority, source, clock);
    }

    private static UUID baselineId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "Facility Accounting Baseline request ID is required");
        }
        return UUID.nameUUIDFromBytes(
                (FacilityAdministration.SERVICE_IDENTITY.value()
                                + ":baseline:"
                                + requestId)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
