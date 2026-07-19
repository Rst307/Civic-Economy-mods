package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredOnlineTimeObservationPolicy;

public final class OnlineTimeObservationPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public OnlineTimeObservationPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Online Time Observation policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public OnlineTimeObservationPolicyVersion schedule(
            ScheduleOnlineTimeObservationPolicy request) {
        StoredOnlineTimeObservationPolicy replay = database.onlineTimeObservationPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Online Time Observation policy must take effect in the future");
        }
        return toVersion(database.scheduleOnlineTimeObservationPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.policy().checkpointInterval().toMillis(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<OnlineTimeObservationPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException(
                    "Online Time Observation policy lookup time is required");
        }
        return Optional.ofNullable(database.currentOnlineTimeObservationPolicy(
                        asOf.toEpochMilli()))
                .map(OnlineTimeObservationPolicyRegistry::toVersion);
    }

    private static void requirePayload(
            StoredOnlineTimeObservationPolicy stored,
            ScheduleOnlineTimeObservationPolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.checkpointIntervalMillis()
                        != request.policy().checkpointInterval().toMillis()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static OnlineTimeObservationPolicyVersion toVersion(
            StoredOnlineTimeObservationPolicy stored) {
        return new OnlineTimeObservationPolicyVersion(
                stored.policyId(),
                new OnlineTimeObservationPolicy(
                        Duration.ofMillis(stored.checkpointIntervalMillis())),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
