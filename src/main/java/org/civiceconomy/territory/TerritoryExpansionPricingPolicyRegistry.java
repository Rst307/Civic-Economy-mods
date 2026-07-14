package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTerritoryExpansionPricingPolicy;

public final class TerritoryExpansionPricingPolicyRegistry {
    private final CivicDatabase database;
    private final Clock clock;
    private final TerritoryExpansionPricingPolicyVersion defaultPolicy;

    public TerritoryExpansionPricingPolicyRegistry(
            CivicDatabase database,
            Clock clock,
            TerritoryExpansionPricingPolicyVersion defaultPolicy) {
        if (database == null || clock == null || defaultPolicy == null) {
            throw new IllegalArgumentException("Territory Expansion pricing dependencies cannot be null");
        }
        if (!defaultPolicy.defaultPolicy()) {
            throw new IllegalArgumentException("Territory Expansion pricing fallback must be a default policy");
        }
        this.database = database;
        this.clock = clock;
        this.defaultPolicy = defaultPolicy;
    }

    public TerritoryExpansionPricingPolicyVersion schedule(
            ScheduleTerritoryExpansionPricingPolicy request) {
        StoredTerritoryExpansionPricingPolicy replay = database.territoryExpansionPricingPolicy(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toPolicy(replay);
        }
        if (request.effectiveAt().toEpochMilli() <= clock.millis()) {
            throw new IllegalArgumentException(
                    "Territory Expansion pricing must take effect in the future");
        }
        return toPolicy(database.scheduleTerritoryExpansionPricingPolicy(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorIdentity(),
                request.firstOverageChunkCost(),
                request.additionalMarginalCost(),
                request.effectiveAt().toEpochMilli(),
                request.reason(),
                clock.millis()));
    }

    public TerritoryExpansionPricingPolicyVersion current(Instant asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("Territory Expansion pricing time cannot be null");
        }
        StoredTerritoryExpansionPricingPolicy stored =
                database.currentTerritoryExpansionPricingPolicy(asOf.toEpochMilli());
        return stored == null ? defaultPolicy : toPolicy(stored);
    }

    private static void requirePayload(
            StoredTerritoryExpansionPricingPolicy stored,
            ScheduleTerritoryExpansionPricingPolicy request) {
        if (!stored.actorIdentity().equals(request.actorIdentity())
                || stored.firstOverageChunkCost() != request.firstOverageChunkCost()
                || stored.additionalMarginalCost() != request.additionalMarginalCost()
                || stored.effectiveAtEpochMillis() != request.effectiveAt().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static TerritoryExpansionPricingPolicyVersion toPolicy(
            StoredTerritoryExpansionPricingPolicy stored) {
        return new TerritoryExpansionPricingPolicyVersion(
                stored.policyId(),
                stored.firstOverageChunkCost(),
                stored.additionalMarginalCost(),
                Instant.ofEpochMilli(stored.effectiveAtEpochMillis()),
                stored.actorIdentity(),
                stored.reason(),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()),
                false);
    }
}
