package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTerritoryForceLoadEnforcement;

public final class TerritoryForceLoadEnforcementRegistry {
    public static final Duration FORCE_LOAD_GRACE = Duration.ofHours(24);

    private final CivicDatabase database;
    private final Clock clock;

    public TerritoryForceLoadEnforcementRegistry(CivicDatabase database) {
        this(database, Clock.systemUTC());
    }

    public TerritoryForceLoadEnforcementRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Territory Force-load Enforcement dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public TerritoryForceLoadEnforcement prepare(
            ServiceIdentity serviceIdentity,
            String requestId,
            UUID assessmentId,
            String reason) {
        requireRequest(serviceIdentity, requestId, assessmentId, reason);
        StoredTerritoryForceLoadEnforcement replay =
                database.territoryForceLoadEnforcement(serviceIdentity.value(), requestId);
        if (replay != null) {
            requireReplay(replay, serviceIdentity, requestId, assessmentId, reason);
            return toEnforcement(replay);
        }
        return toEnforcement(database.prepareTerritoryForceLoadEnforcement(
                UUID.randomUUID(),
                serviceIdentity.value(),
                requestId,
                assessmentId,
                reason,
                clock.millis()));
    }

    public Optional<TerritoryForceLoadEnforcement> find(UUID enforcementId) {
        if (enforcementId == null) {
            throw new IllegalArgumentException("Enforcement id cannot be null");
        }
        return Optional.ofNullable(database.territoryForceLoadEnforcement(enforcementId))
                .map(TerritoryForceLoadEnforcementRegistry::toEnforcement);
    }

    public List<TerritoryForceLoadEnforcement> incomplete() {
        return database.dueTerritoryForceLoadEnforcements(clock.millis()).stream()
                .map(TerritoryForceLoadEnforcementRegistry::toEnforcement)
                .toList();
    }

    public TerritoryForceLoadEnforcement markExternalApplied(UUID enforcementId) {
        if (enforcementId == null) {
            throw new IllegalArgumentException("Enforcement id cannot be null");
        }
        TerritoryForceLoadEnforcement existing = find(enforcementId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Territory Force-load Enforcement " + enforcementId));
        if (clock.instant().isBefore(existing.notBefore())) {
            throw new IllegalStateException(
                    "Territory Force-load Enforcement grace has not elapsed");
        }
        return toEnforcement(database.markTerritoryForceLoadExternalApplied(
                enforcementId, clock.millis()));
    }

    public TerritoryForceLoadEnforcement commit(UUID enforcementId) {
        if (enforcementId == null) {
            throw new IllegalArgumentException("Enforcement id cannot be null");
        }
        return toEnforcement(database.commitTerritoryForceLoadEnforcement(
                enforcementId, clock.millis()));
    }

    private static void requireRequest(
            ServiceIdentity serviceIdentity,
            String requestId,
            UUID assessmentId,
            String reason) {
        if (serviceIdentity == null
                || requestId == null
                || assessmentId == null
                || reason == null
                || requestId.isBlank()
                || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Territory Force-load Enforcement request is invalid");
        }
    }

    private static void requireReplay(
            StoredTerritoryForceLoadEnforcement stored,
            ServiceIdentity serviceIdentity,
            String requestId,
            UUID assessmentId,
            String reason) {
        if (!stored.serviceIdentity().equals(serviceIdentity.value())
                || !stored.requestId().equals(requestId)
                || !stored.assessmentId().equals(assessmentId)
                || !stored.reason().equals(reason)) {
            throw new IdempotencyConflictException(serviceIdentity, requestId);
        }
    }

    private static TerritoryForceLoadEnforcement toEnforcement(
            StoredTerritoryForceLoadEnforcement stored) {
        return new TerritoryForceLoadEnforcement(
                stored.enforcementId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.assessmentId(),
                stored.ftbTeamId(),
                new TerritoryClaimPosition(
                        stored.dimensionId(), stored.chunkX(), stored.chunkZ()),
                TerritoryForceLoadEnforcementState.valueOf(stored.state()),
                stored.reason(),
                Instant.ofEpochMilli(stored.notBeforeEpochMillis()),
                Instant.ofEpochMilli(stored.preparedAtEpochMillis()),
                stored.externalAppliedAtEpochMillis() == null
                        ? null
                        : Instant.ofEpochMilli(stored.externalAppliedAtEpochMillis()),
                stored.committedAtEpochMillis() == null
                        ? null
                        : Instant.ofEpochMilli(stored.committedAtEpochMillis()));
    }
}
