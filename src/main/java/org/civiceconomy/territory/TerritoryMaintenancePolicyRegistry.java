package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTerritoryMaintenancePolicy;

public final class TerritoryMaintenancePolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public TerritoryMaintenancePolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public TerritoryMaintenancePolicyVersion schedule(
            ScheduleTerritoryMaintenancePolicy request) {
        StoredTerritoryMaintenancePolicy replay = database.territoryMaintenancePolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toPolicy(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance policy must take effect in the future");
        }
        return toPolicy(database.scheduleTerritoryMaintenancePolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.cycleDuration().toMillis(),
                request.baseMaintenancePerChargeableClaimMinorUnits(),
                request.enclaveAndCrossDimensionMultiplierBasisPoints(),
                request.forceLoadSurchargeMinorUnits(),
                request.destructionBasisPoints(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<TerritoryMaintenancePolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("Territory Maintenance policy time cannot be null");
        }
        return Optional.ofNullable(database.currentTerritoryMaintenancePolicy(asOf.toEpochMilli()))
                .map(TerritoryMaintenancePolicyRegistry::toPolicy);
    }

    private static void requirePayload(
            StoredTerritoryMaintenancePolicy stored,
            ScheduleTerritoryMaintenancePolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.cycleDurationMillis() != request.cycleDuration().toMillis()
                || stored.baseMaintenancePerChargeableClaimMinorUnits()
                        != request.baseMaintenancePerChargeableClaimMinorUnits()
                || stored.enclaveAndCrossDimensionMultiplierBasisPoints()
                        != request.enclaveAndCrossDimensionMultiplierBasisPoints()
                || stored.forceLoadSurchargeMinorUnits() != request.forceLoadSurchargeMinorUnits()
                || stored.destructionBasisPoints() != request.destructionBasisPoints()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static TerritoryMaintenancePolicyVersion toPolicy(
            StoredTerritoryMaintenancePolicy stored) {
        return new TerritoryMaintenancePolicyVersion(
                stored.policyId(),
                Duration.ofMillis(stored.cycleDurationMillis()),
                stored.baseMaintenancePerChargeableClaimMinorUnits(),
                stored.enclaveAndCrossDimensionMultiplierBasisPoints(),
                stored.forceLoadSurchargeMinorUnits(),
                stored.destructionBasisPoints(),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
