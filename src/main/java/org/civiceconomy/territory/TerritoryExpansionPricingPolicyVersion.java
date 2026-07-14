package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;

public record TerritoryExpansionPricingPolicyVersion(
        UUID policyId,
        long firstOverageChunkCost,
        long additionalMarginalCost,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt,
        boolean defaultPolicy) {
    private static final UUID DEFAULT_POLICY_ID = new UUID(0L, 0L);

    public TerritoryExpansionPricingPolicyVersion {
        if (policyId == null
                || effectiveAt == null
                || actorIdentity == null
                || reason == null
                || recordedAt == null) {
            throw new IllegalArgumentException("Territory Expansion pricing policy cannot contain null values");
        }
        if (firstOverageChunkCost < 0L || additionalMarginalCost < 0L) {
            throw new IllegalArgumentException("Territory Expansion pricing cannot be negative");
        }
        if (actorIdentity.isBlank() || reason.isBlank()) {
            throw new IllegalArgumentException("Territory Expansion pricing audit cannot be blank");
        }
    }

    public static TerritoryExpansionPricingPolicyVersion defaultPolicy(
            long firstOverageChunkCost, long additionalMarginalCost) {
        return new TerritoryExpansionPricingPolicyVersion(
                DEFAULT_POLICY_ID,
                firstOverageChunkCost,
                additionalMarginalCost,
                Instant.EPOCH,
                "civiceconomy-default",
                "Conservative built-in Territory Expansion pricing default",
                Instant.EPOCH,
                true);
    }

    public TerritoryExpansionPricingPolicy policy() {
        return new TerritoryExpansionPricingPolicy(
                firstOverageChunkCost, additionalMarginalCost);
    }
}
