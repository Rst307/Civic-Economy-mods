package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredNationApplicationExpiryPolicy;

public final class NationApplicationExpiryPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public NationApplicationExpiryPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Nation Application expiry policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public NationApplicationExpiryPolicyVersion schedule(
            ScheduleNationApplicationExpiryPolicy request) {
        StoredNationApplicationExpiryPolicy replay = database.nationApplicationExpiryPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.actorIdentity().equals(request.actorIdentity())
                    || replay.scanIntervalMillis() != request.policy().scanInterval().toMillis()
                    || replay.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
            }
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException("Nation Application expiry policy must take effect in the future");
        }
        return toVersion(database.scheduleNationApplicationExpiryPolicy(
                UUID.randomUUID(), request.serviceIdentity().value(), request.requestId(),
                request.actorIdentity(), request.policy().scanInterval().toMillis(),
                request.effectiveAt().toEpochMilli(), request.reason(), clock.millis()));
    }

    public Optional<NationApplicationExpiryPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException(
                    "Nation Application expiry policy lookup time is required");
        }
        return Optional.ofNullable(database.currentNationApplicationExpiryPolicy(asOf.toEpochMilli()))
                .map(NationApplicationExpiryPolicyRegistry::toVersion);
    }

    private static NationApplicationExpiryPolicyVersion toVersion(
            StoredNationApplicationExpiryPolicy stored) {
        return new NationApplicationExpiryPolicyVersion(
                stored.policyId(),
                new NationApplicationExpiryPolicy(Duration.ofMillis(stored.scanIntervalMillis())),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()), stored.actorIdentity(),
                stored.reason(), Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
