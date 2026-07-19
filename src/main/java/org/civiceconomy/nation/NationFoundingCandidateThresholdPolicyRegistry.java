package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredNationFoundingCandidateThresholdPolicy;

public final class NationFoundingCandidateThresholdPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public NationFoundingCandidateThresholdPolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Formal founding threshold policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public NationFoundingCandidateThresholdPolicyVersion schedule(
            ScheduleNationFoundingCandidateThresholdPolicy request) {
        StoredNationFoundingCandidateThresholdPolicy replay =
                database.nationFoundingCandidateThresholdPolicy(
                        request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.actorIdentity().equals(request.actorIdentity())
                    || replay.minimumEffectiveCandidates()
                            != request.policy().minimumEffectiveCandidates()
                    || replay.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
            }
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException("Formal founding threshold policy must take effect in the future");
        }
        return toVersion(database.scheduleNationFoundingCandidateThresholdPolicy(
                UUID.randomUUID(), request.serviceIdentity().value(), request.requestId(),
                request.actorIdentity(), request.policy().minimumEffectiveCandidates(),
                request.effectiveAt().toEpochMilli(), request.reason(), clock.millis()));
    }

    public Optional<NationFoundingCandidateThresholdPolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("Formal founding threshold lookup time is required");
        }
        return Optional.ofNullable(database.currentNationFoundingCandidateThresholdPolicy(
                        asOf.toEpochMilli()))
                .map(NationFoundingCandidateThresholdPolicyRegistry::toVersion);
    }

    private static NationFoundingCandidateThresholdPolicyVersion toVersion(
            StoredNationFoundingCandidateThresholdPolicy stored) {
        return new NationFoundingCandidateThresholdPolicyVersion(
                stored.policyId(),
                new NationFoundingCandidateThresholdPolicy(stored.minimumEffectiveCandidates()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()), stored.actorIdentity(),
                stored.reason(), Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
