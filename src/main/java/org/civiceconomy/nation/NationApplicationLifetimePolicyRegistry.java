package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredNationApplicationLifetimePolicy;

public final class NationApplicationLifetimePolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public NationApplicationLifetimePolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Nation Application lifetime policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public NationApplicationLifetimePolicyVersion schedule(
            ScheduleNationApplicationLifetimePolicy request) {
        StoredNationApplicationLifetimePolicy replay = database.nationApplicationLifetimePolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.actorIdentity().equals(request.actorIdentity())
                    || replay.lifetimeMillis() != request.policy().lifetime().toMillis()
                    || replay.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
            }
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException("Nation Application lifetime policy must take effect in the future");
        }
        return toVersion(database.scheduleNationApplicationLifetimePolicy(
                UUID.randomUUID(), request.serviceIdentity().value(), request.requestId(),
                request.actorIdentity(), request.policy().lifetime().toMillis(),
                request.effectiveAt().toEpochMilli(), request.reason(), clock.millis()));
    }

    public Optional<NationApplicationLifetimePolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("Nation Application lifetime policy lookup time is required");
        }
        return Optional.ofNullable(database.currentNationApplicationLifetimePolicy(asOf.toEpochMilli()))
                .map(NationApplicationLifetimePolicyRegistry::toVersion);
    }

    private static NationApplicationLifetimePolicyVersion toVersion(
            StoredNationApplicationLifetimePolicy stored) {
        return new NationApplicationLifetimePolicyVersion(
                stored.policyId(),
                new NationApplicationLifetimePolicy(Duration.ofMillis(stored.lifetimeMillis())),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(), stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
