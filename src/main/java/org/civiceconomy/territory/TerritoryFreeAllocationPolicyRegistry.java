package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTerritoryFreeAllocationPolicy;

public final class TerritoryFreeAllocationPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;
    private final TerritoryFreeAllocationPolicyVersion defaultPolicy;

    public TerritoryFreeAllocationPolicyRegistry(
            CivicDatabase database,
            Clock clock,
            TerritoryFreeAllocationPolicyVersion defaultPolicy) {
        if (database == null || clock == null || defaultPolicy == null) {
            throw new IllegalArgumentException("Territory Free Allocation policy dependencies cannot be null");
        }
        if (!defaultPolicy.defaultPolicy()) {
            throw new IllegalArgumentException("Territory Free Allocation fallback must be a default policy");
        }
        this.database = database;
        this.clock = clock;
        this.defaultPolicy = defaultPolicy;
    }

    public TerritoryFreeAllocationPolicyVersion schedule(
            ScheduleTerritoryFreeAllocationPolicy request) {
        StoredTerritoryFreeAllocationPolicy replay = database.territoryFreeAllocationPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toPolicy(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Territory Free Allocation policy must take effect in the future");
        }
        return toPolicy(database.scheduleTerritoryFreeAllocationPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.baseChunks(),
                request.chunksPerEffectiveCitizen(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public TerritoryFreeAllocationPolicyVersion current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("Territory Free Allocation policy time cannot be null");
        }
        StoredTerritoryFreeAllocationPolicy stored =
                database.currentTerritoryFreeAllocationPolicy(asOf.toEpochMilli());
        return stored == null ? defaultPolicy : toPolicy(stored);
    }

    private static void requirePayload(
            StoredTerritoryFreeAllocationPolicy stored,
            ScheduleTerritoryFreeAllocationPolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.baseChunks() != request.baseChunks()
                || stored.chunksPerEffectiveCitizen() != request.chunksPerEffectiveCitizen()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static TerritoryFreeAllocationPolicyVersion toPolicy(
            StoredTerritoryFreeAllocationPolicy stored) {
        return new TerritoryFreeAllocationPolicyVersion(
                stored.policyId(),
                stored.baseChunks(),
                stored.chunksPerEffectiveCitizen(),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()),
                false);
    }
}
