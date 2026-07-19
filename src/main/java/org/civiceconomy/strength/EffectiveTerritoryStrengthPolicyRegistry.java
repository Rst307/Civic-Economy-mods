package org.civiceconomy.strength;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredEffectiveTerritoryStrengthPolicy;

public final class EffectiveTerritoryStrengthPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public EffectiveTerritoryStrengthPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Effective Territory Strength policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public EffectiveTerritoryStrengthPolicyVersion schedule(
            ScheduleEffectiveTerritoryStrengthPolicy request) {
        StoredEffectiveTerritoryStrengthPolicy replay =
                database.effectiveTerritoryStrengthPolicy(
                        request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Effective Territory Strength policy must take effect in the future");
        }
        return toVersion(database.scheduleEffectiveTerritoryStrengthPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.policy().fullStrengthScaleEffectiveClaims(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<EffectiveTerritoryStrengthPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException(
                    "Effective Territory Strength policy lookup time is required");
        }
        return Optional.ofNullable(
                        database.currentEffectiveTerritoryStrengthPolicy(asOf.toEpochMilli()))
                .map(EffectiveTerritoryStrengthPolicyRegistry::toVersion);
    }

    private static void requirePayload(
            StoredEffectiveTerritoryStrengthPolicy stored,
            ScheduleEffectiveTerritoryStrengthPolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.fullStrengthScaleEffectiveClaims()
                        != request.policy().fullStrengthScaleEffectiveClaims()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static EffectiveTerritoryStrengthPolicyVersion toVersion(
            StoredEffectiveTerritoryStrengthPolicy stored) {
        return new EffectiveTerritoryStrengthPolicyVersion(
                stored.policyId(),
                new EffectiveTerritoryStrengthPolicy(
                        stored.fullStrengthScaleEffectiveClaims()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
