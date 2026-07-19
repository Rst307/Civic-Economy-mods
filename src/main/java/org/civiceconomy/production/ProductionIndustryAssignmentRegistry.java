package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredProductionIndustryAssignment;

public final class ProductionIndustryAssignmentRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public ProductionIndustryAssignmentRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Production Industry assignment dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public ProductionIndustryAssignmentVersion schedule(
            ScheduleProductionIndustryAssignment request) {
        StoredProductionIndustryAssignment replay = database.productionIndustryAssignment(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Production Industry assignment must take effect in the future");
        }
        return toVersion(database.scheduleProductionIndustryAssignment(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.createVersion(),
                request.recipeId(),
                request.industryId().value(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<ProductionIndustryAssignmentVersion> assignment(
            String createVersion, String recipeId, Instant asOf) {
        if (createVersion == null || createVersion.isBlank()
                || recipeId == null || !recipeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || asOf == null || asOf.toEpochMilli() < 0L) {
            throw new IllegalArgumentException(
                    "Production Industry assignment lookup is invalid");
        }
        return Optional.ofNullable(database.currentProductionIndustryAssignment(
                        createVersion, recipeId, asOf.toEpochMilli()))
                .map(ProductionIndustryAssignmentRegistry::toVersion);
    }

    private static void requirePayload(
            StoredProductionIndustryAssignment stored,
            ScheduleProductionIndustryAssignment request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || !stored.createVersion().equals(request.createVersion())
                || !stored.recipeId().equals(request.recipeId())
                || !stored.industryId().equals(request.industryId().value())
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static ProductionIndustryAssignmentVersion toVersion(
            StoredProductionIndustryAssignment stored) {
        return new ProductionIndustryAssignmentVersion(
                stored.assignmentId(),
                stored.createVersion(),
                stored.recipeId(),
                new ProductionIndustryId(stored.industryId()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
