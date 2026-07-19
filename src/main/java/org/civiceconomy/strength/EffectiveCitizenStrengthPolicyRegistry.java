package org.civiceconomy.strength;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredEffectiveCitizenStrengthPolicy;

public final class EffectiveCitizenStrengthPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public EffectiveCitizenStrengthPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Effective Citizen Strength policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public EffectiveCitizenStrengthPolicyVersion schedule(
            ScheduleEffectiveCitizenStrengthPolicy request) {
        StoredEffectiveCitizenStrengthPolicy replay =
                database.effectiveCitizenStrengthPolicy(
                        request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Effective Citizen Strength policy must take effect in the future");
        }
        return toVersion(database.scheduleEffectiveCitizenStrengthPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.policy().fullStrengthScaleCitizenEquivalents(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<EffectiveCitizenStrengthPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException(
                    "Effective Citizen Strength policy lookup time is required");
        }
        return Optional.ofNullable(
                        database.currentEffectiveCitizenStrengthPolicy(asOf.toEpochMilli()))
                .map(EffectiveCitizenStrengthPolicyRegistry::toVersion);
    }

    private static void requirePayload(
            StoredEffectiveCitizenStrengthPolicy stored,
            ScheduleEffectiveCitizenStrengthPolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.fullStrengthScaleCitizenEquivalents()
                        != request.policy().fullStrengthScaleCitizenEquivalents()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static EffectiveCitizenStrengthPolicyVersion toVersion(
            StoredEffectiveCitizenStrengthPolicy stored) {
        return new EffectiveCitizenStrengthPolicyVersion(
                stored.policyId(),
                new EffectiveCitizenStrengthPolicy(
                        stored.fullStrengthScaleCitizenEquivalents()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
