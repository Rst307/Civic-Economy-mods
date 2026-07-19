package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredRegisteredFacilityScopePolicy;

public final class RegisteredFacilityScopePolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public RegisteredFacilityScopePolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Registered Facility Scope policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public RegisteredFacilityScopePolicyVersion schedule(
            ScheduleRegisteredFacilityScopePolicy request) {
        StoredRegisteredFacilityScopePolicy replay = database.registeredFacilityScopePolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Registered Facility Scope policy must take effect in the future");
        }
        return toVersion(database.scheduleRegisteredFacilityScopePolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.policy().maxScopeChunks(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<RegisteredFacilityScopePolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException(
                    "Registered Facility Scope policy lookup time is required");
        }
        return Optional.ofNullable(database.currentRegisteredFacilityScopePolicy(
                        asOf.toEpochMilli()))
                .map(RegisteredFacilityScopePolicyRegistry::toVersion);
    }

    private static void requirePayload(
            StoredRegisteredFacilityScopePolicy stored,
            ScheduleRegisteredFacilityScopePolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.maxScopeChunks() != request.policy().maxScopeChunks()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
        }
    }

    private static RegisteredFacilityScopePolicyVersion toVersion(
            StoredRegisteredFacilityScopePolicy stored) {
        return new RegisteredFacilityScopePolicyVersion(
                stored.policyId(),
                new RegisteredFacilityScopePolicy(stored.maxScopeChunks()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
