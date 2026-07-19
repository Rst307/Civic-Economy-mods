package org.civiceconomy.production;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredProductionStrengthPolicy;

public final class ProductionStrengthPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public ProductionStrengthPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Production Strength policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public ProductionStrengthPolicyVersion schedule(ScheduleProductionStrengthPolicy request) {
        StoredProductionStrengthPolicy replay = database.productionStrengthPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Production Strength policy must take effect in the future");
        }
        ProductionStrengthPolicy policy = request.policy();
        return toVersion(database.scheduleProductionStrengthPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                policy.observationWindowMillis(),
                policy.fullWeightWindowMillis(),
                policy.fullStrengthScaleMinorUnits(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<ProductionStrengthPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("Production Strength policy lookup time is required");
        }
        return Optional.ofNullable(database.currentProductionStrengthPolicy(asOf.toEpochMilli()))
                .map(ProductionStrengthPolicyRegistry::toVersion);
    }

    private static void requirePayload(
            StoredProductionStrengthPolicy stored,
            ScheduleProductionStrengthPolicy request) {
        ProductionStrengthPolicy policy = request.policy();
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.observationWindowMillis() != policy.observationWindowMillis()
                || stored.fullWeightWindowMillis() != policy.fullWeightWindowMillis()
                || stored.fullStrengthScaleMinorUnits()
                        != policy.fullStrengthScaleMinorUnits()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static ProductionStrengthPolicyVersion toVersion(
            StoredProductionStrengthPolicy stored) {
        return new ProductionStrengthPolicyVersion(
                stored.policyId(),
                new ProductionStrengthPolicy(
                        Duration.ofMillis(stored.observationWindowMillis()),
                        Duration.ofMillis(stored.fullWeightWindowMillis()),
                        stored.fullStrengthScaleMinorUnits()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
