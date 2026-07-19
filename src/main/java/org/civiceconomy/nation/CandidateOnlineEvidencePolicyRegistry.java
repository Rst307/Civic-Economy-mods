package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredCandidateOnlineEvidencePolicy;

public final class CandidateOnlineEvidencePolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public CandidateOnlineEvidencePolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Candidate Online Evidence policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public CandidateOnlineEvidencePolicyVersion schedule(
            ScheduleCandidateOnlineEvidencePolicy request) {
        StoredCandidateOnlineEvidencePolicy replay = database.candidateOnlineEvidencePolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.actorIdentity().equals(request.actorIdentity())
                    || replay.observationWindowMillis()
                            != request.policy().observationWindow().toMillis()
                    || replay.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Candidate Online Evidence policy must take effect in the future");
        }
        return toVersion(database.scheduleCandidateOnlineEvidencePolicy(
                UUID.randomUUID(), request.serviceIdentity().value(), request.requestId(),
                request.actorIdentity(), request.policy().observationWindow().toMillis(),
                request.effectiveAt().toEpochMilli(), request.reason(), clock.millis()));
    }

    public Optional<CandidateOnlineEvidencePolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException(
                    "Candidate Online Evidence policy lookup time is required");
        }
        return Optional.ofNullable(database.currentCandidateOnlineEvidencePolicy(
                        asOf.toEpochMilli()))
                .map(CandidateOnlineEvidencePolicyRegistry::toVersion);
    }

    private static CandidateOnlineEvidencePolicyVersion toVersion(
            StoredCandidateOnlineEvidencePolicy stored) {
        return new CandidateOnlineEvidencePolicyVersion(
                stored.policyId(),
                new CandidateOnlineEvidencePolicy(
                        Duration.ofMillis(stored.observationWindowMillis())),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(), stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
