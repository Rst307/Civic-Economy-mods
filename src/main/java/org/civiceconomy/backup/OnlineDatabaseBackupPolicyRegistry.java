package org.civiceconomy.backup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredOnlineDatabaseBackupPolicy;

public final class OnlineDatabaseBackupPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public OnlineDatabaseBackupPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Online database backup policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public OnlineDatabaseBackupPolicyVersion schedule(
            ScheduleOnlineDatabaseBackupPolicy request) {
        StoredOnlineDatabaseBackupPolicy replay = database.onlineDatabaseBackupPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.actorIdentity().equals(request.actorIdentity())
                    || replay.intervalMillis() != request.policy().interval().toMillis()
                    || replay.retention() != request.policy().retention()
                    || replay.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Online database backup policy must take effect in the future");
        }
        return toVersion(database.scheduleOnlineDatabaseBackupPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.policy().interval().toMillis(),
                request.policy().retention(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<OnlineDatabaseBackupPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException(
                    "Online database backup policy lookup time is required");
        }
        return Optional.ofNullable(
                        database.currentOnlineDatabaseBackupPolicy(asOf.toEpochMilli()))
                .map(OnlineDatabaseBackupPolicyRegistry::toVersion);
    }

    private static OnlineDatabaseBackupPolicyVersion toVersion(
            StoredOnlineDatabaseBackupPolicy stored) {
        return new OnlineDatabaseBackupPolicyVersion(
                stored.policyId(),
                new OnlineDatabaseBackupPolicy(
                        Duration.ofMillis(stored.intervalMillis()), stored.retention()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
