package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredEffectiveCitizenPopulationPolicy;

public final class EffectiveCitizenPopulationPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public EffectiveCitizenPopulationPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Effective Citizen population policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public EffectiveCitizenPopulationPolicyVersion schedule(
            ScheduleEffectiveCitizenPopulationPolicy request) {
        StoredEffectiveCitizenPopulationPolicy replay = database.effectiveCitizenPopulationPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.actorIdentity().equals(request.actorIdentity())
                    || replay.observationWindowMillis()
                            != request.policy().observationWindow().toMillis()
                    || replay.fullContributionTimeMillis()
                            != request.policy().fullContributionTime().toMillis()
                    || replay.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
            }
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException("Effective Citizen population policy must take effect in the future");
        }
        return toVersion(database.scheduleEffectiveCitizenPopulationPolicy(
                UUID.randomUUID(), request.serviceIdentity().value(), request.requestId(),
                request.actorIdentity(), request.policy().observationWindow().toMillis(),
                request.policy().fullContributionTime().toMillis(),
                request.effectiveAt().toEpochMilli(), request.reason(), clock.millis()));
    }

    public Optional<EffectiveCitizenPopulationPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("Effective Citizen population policy lookup time is required");
        }
        return Optional.ofNullable(database.currentEffectiveCitizenPopulationPolicy(asOf.toEpochMilli()))
                .map(EffectiveCitizenPopulationPolicyRegistry::toVersion);
    }

    private static EffectiveCitizenPopulationPolicyVersion toVersion(
            StoredEffectiveCitizenPopulationPolicy stored) {
        return new EffectiveCitizenPopulationPolicyVersion(
                stored.policyId(),
                new EffectiveCitizenPopulationPolicy(
                        Duration.ofMillis(stored.observationWindowMillis()),
                        Duration.ofMillis(stored.fullContributionTimeMillis())),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()), stored.actorIdentity(),
                stored.reason(), Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
