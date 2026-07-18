package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredProductionMarginalReturnPolicy;

public final class ProductionMarginalReturnPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public ProductionMarginalReturnPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Production Marginal Return policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public ProductionMarginalReturnPolicyVersion schedule(
            ScheduleProductionMarginalReturnPolicy request) {
        StoredProductionMarginalReturnPolicy replay =
                database.productionMarginalReturnPolicy(
                        request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Production Marginal Return policy must take effect in the future");
        }
        ProductionMarginalReturnPolicy policy = request.policy();
        return toVersion(database.scheduleProductionMarginalReturnPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                policy.facilitySoftCapMinorUnits(),
                policy.facilityExcessWeightBasisPoints(),
                policy.industrySoftCapMinorUnits(),
                policy.industryExcessWeightBasisPoints(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<ProductionMarginalReturnPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException(
                    "Production Marginal Return policy lookup time is required");
        }
        return Optional.ofNullable(
                        database.currentProductionMarginalReturnPolicy(asOf.toEpochMilli()))
                .map(ProductionMarginalReturnPolicyRegistry::toVersion);
    }

    private static void requirePayload(
            StoredProductionMarginalReturnPolicy stored,
            ScheduleProductionMarginalReturnPolicy request) {
        ProductionMarginalReturnPolicy policy = request.policy();
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.facilitySoftCapMinorUnits()
                        != policy.facilitySoftCapMinorUnits()
                || stored.facilityExcessWeightBasisPoints()
                        != policy.facilityExcessWeightBasisPoints()
                || stored.industrySoftCapMinorUnits()
                        != policy.industrySoftCapMinorUnits()
                || stored.industryExcessWeightBasisPoints()
                        != policy.industryExcessWeightBasisPoints()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static ProductionMarginalReturnPolicyVersion toVersion(
            StoredProductionMarginalReturnPolicy stored) {
        return new ProductionMarginalReturnPolicyVersion(
                stored.policyId(),
                new ProductionMarginalReturnPolicy(
                        stored.facilitySoftCapMinorUnits(),
                        stored.facilityExcessWeightBasisPoints(),
                        stored.industrySoftCapMinorUnits(),
                        stored.industryExcessWeightBasisPoints()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
