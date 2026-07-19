package org.civiceconomy.strength;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredMintCompliancePolicy;

public final class MintCompliancePolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public MintCompliancePolicyRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Mint Compliance policy dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public MintCompliancePolicyVersion schedule(ScheduleMintCompliancePolicy request) {
        StoredMintCompliancePolicy replay = database.mintCompliancePolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toVersion(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException("Mint Compliance policy must take effect in the future");
        }
        return toVersion(database.scheduleMintCompliancePolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.policy().observationWindow().toMillis(),
                request.policy().recoveredCommitBasisPoints(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public Optional<MintCompliancePolicyVersion> current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("Mint Compliance policy lookup time is required");
        }
        return Optional.ofNullable(database.currentMintCompliancePolicy(asOf.toEpochMilli()))
                .map(MintCompliancePolicyRegistry::toVersion);
    }

    private static void requirePayload(
            StoredMintCompliancePolicy stored, ScheduleMintCompliancePolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.observationWindowMillis() != request.policy().observationWindow().toMillis()
                || stored.recoveredCommitBasisPoints()
                        != request.policy().recoveredCommitBasisPoints()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
        }
    }

    private static MintCompliancePolicyVersion toVersion(StoredMintCompliancePolicy stored) {
        return new MintCompliancePolicyVersion(
                stored.policyId(),
                new MintCompliancePolicy(
                        Duration.ofMillis(stored.observationWindowMillis()),
                        stored.recoveredCommitBasisPoints()),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
