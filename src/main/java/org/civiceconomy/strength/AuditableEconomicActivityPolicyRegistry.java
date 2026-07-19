package org.civiceconomy.strength;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredAuditableEconomicActivityPolicy;

public final class AuditableEconomicActivityPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public AuditableEconomicActivityPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public AuditableEconomicActivityPolicyVersion schedule(
            ScheduleAuditableEconomicActivityPolicy request) {
        StoredAuditableEconomicActivityPolicy replay = database.auditableEconomicActivityPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity policy must take effect in the future");
        }
        return toVersion(database.scheduleAuditableEconomicActivityPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.policy().observationWindow().toMillis(),
                request.policy().fullStrengthScaleMinorUnits(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<AuditableEconomicActivityPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity policy lookup time is required");
        }
        return Optional.ofNullable(
                        database.currentAuditableEconomicActivityPolicy(asOf.toEpochMilli()))
                .map(AuditableEconomicActivityPolicyRegistry::toVersion);
    }

    private static void requirePayload(
            StoredAuditableEconomicActivityPolicy stored,
            ScheduleAuditableEconomicActivityPolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.observationWindowMillis()
                        != request.policy().observationWindow().toMillis()
                || stored.fullStrengthScaleMinorUnits()
                        != request.policy().fullStrengthScaleMinorUnits()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
        }
    }

    private static AuditableEconomicActivityPolicyVersion toVersion(
            StoredAuditableEconomicActivityPolicy stored) {
        return new AuditableEconomicActivityPolicyVersion(
                stored.policyId(),
                new AuditableEconomicActivityPolicy(
                        Duration.ofMillis(stored.observationWindowMillis()),
                        stored.fullStrengthScaleMinorUnits()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
