package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFacilityAccountingInterface;
import org.civiceconomy.persistence.StoredFacilityClaim;

public final class FacilityAccountingInterfaceRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public FacilityAccountingInterfaceRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public FacilityAccountingInterface register(RegisterFacilityAccountingInterface request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface request is required");
        }
        StoredFacilityAccountingInterface replay = database.facilityAccountingInterface(
                request.serviceIdentity().value(), request.requestId());
        if (replay == null) {
            requirePositionInsideFacility(request.facilityId(), request.position());
        }
        return toInterface(database.registerFacilityAccountingInterface(
                request.interfaceId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.facilityId(),
                request.position().dimensionId(),
                request.position().blockX(),
                request.position().blockY(),
                request.position().blockZ(),
                request.actorPlayerId(),
                request.reason(),
                clock.millis()));
    }

    public FacilityAccountingInterface interfaceFor(UUID facilityId) {
        StoredFacilityAccountingInterface stored = database.facilityAccountingInterface(facilityId);
        return stored == null ? null : toInterface(stored);
    }

    private void requirePositionInsideFacility(
            UUID facilityId, FacilityAccountingInterfacePosition position) {
        if (database.registeredFacility(facilityId) == null) {
            throw new IllegalStateException(
                    "Facility Accounting Interface references an unknown Registered Facility");
        }
        StoredFacilityClaim claim = new StoredFacilityClaim(
                position.claim().dimensionId(),
                position.claim().chunkX(),
                position.claim().chunkZ());
        if (!database.registeredFacilityClaims(facilityId).contains(claim)) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface must be inside its Registered Facility scope");
        }
    }

    private static FacilityAccountingInterface toInterface(
            StoredFacilityAccountingInterface stored) {
        return new FacilityAccountingInterface(
                stored.interfaceId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.facilityId(),
                new FacilityAccountingInterfacePosition(
                        stored.dimensionId(),
                        stored.blockX(),
                        stored.blockY(),
                        stored.blockZ()),
                stored.actorPlayerId(),
                stored.reason(),
                Instant.ofEpochMilli(stored.registeredAtEpochMillis()));
    }
}
