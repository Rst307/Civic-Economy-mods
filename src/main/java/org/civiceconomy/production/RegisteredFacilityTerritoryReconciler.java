package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFacilityAccountingBaseline;
import org.civiceconomy.persistence.StoredRegisteredFacility;
import org.civiceconomy.territory.TerritoryClaimPosition;

public final class RegisteredFacilityTerritoryReconciler {
    private static final String TERRITORY_LOST_REASON =
            "Registered Facility scope lost Effective Territory";
    private static final String TERRITORY_RESTORED_REASON =
            "Registered Facility scope restored Effective Territory";

    private final CivicDatabase database;
    private final RegisteredFacilityTerritoryAuthority territoryAuthority;
    private final ServiceIdentity serviceIdentity;
    private final Clock clock;

    public RegisteredFacilityTerritoryReconciler(
            CivicDatabase database,
            RegisteredFacilityTerritoryAuthority territoryAuthority,
            ServiceIdentity serviceIdentity,
            Clock clock) {
        if (database == null || territoryAuthority == null
                || serviceIdentity == null || clock == null) {
            throw new IllegalArgumentException(
                    "Registered Facility Territory reconciliation dependencies are required");
        }
        this.database = database;
        this.territoryAuthority = territoryAuthority;
        this.serviceIdentity = serviceIdentity;
        this.clock = clock;
    }

    public List<RegisteredFacility> reconcile() {
        List<RegisteredFacility> changed = new ArrayList<>();
        for (StoredRegisteredFacility stored : database.registeredFacilities()) {
            if (!serviceIdentity.value().equals(stored.serviceIdentity())) {
                continue;
            }
            RegisteredFacilityState state = RegisteredFacilityState.valueOf(stored.state());
            if (state != RegisteredFacilityState.ACTIVE
                    && state != RegisteredFacilityState.PAUSED_TERRITORY) {
                continue;
            }
            boolean effective = database.registeredFacilityClaims(stored.facilityId()).stream()
                    .allMatch(claim -> territoryAuthority.isEffective(
                            new NationId(stored.nationId()),
                            stored.ftbTeamId(),
                            new TerritoryClaimPosition(
                                    claim.dimensionId(), claim.chunkX(), claim.chunkZ())));
            RegisteredFacilityState next = nextState(stored, state, effective);
            if (next == null) {
                continue;
            }
            StoredRegisteredFacility transitioned = database.transitionRegisteredFacilityState(
                    UUID.randomUUID(),
                    stored.facilityId(),
                    serviceIdentity.value(),
                    state.name(),
                    next.name(),
                    next == RegisteredFacilityState.PAUSED_TERRITORY
                            ? TERRITORY_LOST_REASON
                            : TERRITORY_RESTORED_REASON,
                    clock.millis());
            if (transitioned != null) {
                changed.add(toFacility(transitioned));
            }
        }
        return List.copyOf(changed);
    }

    private RegisteredFacilityState nextState(
            StoredRegisteredFacility facility,
            RegisteredFacilityState state,
            boolean effective) {
        if (state == RegisteredFacilityState.ACTIVE && !effective) {
            return RegisteredFacilityState.PAUSED_TERRITORY;
        }
        if (state != RegisteredFacilityState.PAUSED_TERRITORY || !effective) {
            return null;
        }
        StoredFacilityAccountingBaseline baseline =
                database.facilityAccountingBaseline(facility.facilityId());
        return baseline != null && FacilityAccountingBaselineState.ACTIVE.name().equals(
                        baseline.state())
                ? RegisteredFacilityState.ACTIVE
                : null;
    }

    private RegisteredFacility toFacility(StoredRegisteredFacility stored) {
        return new RegisteredFacility(
                stored.facilityId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new NationId(stored.nationId()),
                stored.ftbTeamId(),
                new FacilityCorePosition(
                        stored.dimensionId(),
                        stored.coreBlockX(),
                        stored.coreBlockY(),
                        stored.coreBlockZ()),
                database.registeredFacilityClaims(stored.facilityId()).stream()
                        .map(claim -> new TerritoryClaimPosition(
                                claim.dimensionId(), claim.chunkX(), claim.chunkZ()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                stored.actorPlayerId(),
                RegisteredFacilityState.valueOf(stored.state()),
                stored.reason(),
                Instant.ofEpochMilli(stored.registeredAtEpochMillis()));
    }
}
