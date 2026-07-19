package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredCitizenshipPolicy;

public final class CitizenshipPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public CitizenshipPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Citizenship policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public CitizenshipPolicyVersion schedule(ScheduleCitizenshipPolicy request) {
        StoredCitizenshipPolicy replay = database.citizenshipPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException("Citizenship policy must take effect in the future");
        }
        return toVersion(database.scheduleCitizenshipPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.policy().correctionGrace().toMillis(),
                request.policy().transferCooldown().toMillis(),
                request.policy().reconciliationInterval().toMillis(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<CitizenshipPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("Citizenship policy lookup time is required");
        }
        return Optional.ofNullable(database.currentCitizenshipPolicy(asOf.toEpochMilli()))
                .map(CitizenshipPolicyRegistry::toVersion);
    }

    private static void requirePayload(
            StoredCitizenshipPolicy stored, ScheduleCitizenshipPolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.correctionGraceMillis() != request.policy().correctionGrace().toMillis()
                || stored.transferCooldownMillis() != request.policy().transferCooldown().toMillis()
                || stored.reconciliationIntervalMillis()
                        != request.policy().reconciliationInterval().toMillis()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
        }
    }

    private static CitizenshipPolicyVersion toVersion(StoredCitizenshipPolicy stored) {
        return new CitizenshipPolicyVersion(
                stored.policyId(),
                new CitizenshipPolicy(
                        Duration.ofMillis(stored.correctionGraceMillis()),
                        Duration.ofMillis(stored.transferCooldownMillis()),
                        Duration.ofMillis(stored.reconciliationIntervalMillis())),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
