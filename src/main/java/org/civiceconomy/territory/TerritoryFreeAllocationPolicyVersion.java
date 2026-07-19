package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;

public record TerritoryFreeAllocationPolicyVersion(
        UUID policyId,
        int baseChunks,
        int chunksPerEffectiveCitizen,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt,
        boolean defaultPolicy) {
    private static final UUID DEFAULT_POLICY_ID = new UUID(0L, 0L);

    public TerritoryFreeAllocationPolicyVersion {
        if (policyId == null
                || effectiveAt == null
                || actorIdentity == null
                || reason == null
                || recordedAt == null) {
            throw new IllegalArgumentException("Territory Free Allocation policy cannot contain null values");
        }
        if (baseChunks < 0 || chunksPerEffectiveCitizen < 0) {
            throw new IllegalArgumentException("Territory Free Allocation policy values cannot be negative");
        }
        if (actorIdentity.isBlank() || reason.isBlank()) {
            throw new IllegalArgumentException("Territory Free Allocation policy audit cannot be blank");
        }
    }

    public static TerritoryFreeAllocationPolicyVersion defaultPolicy(
            int baseChunks, int chunksPerEffectiveCitizen) {
        return new TerritoryFreeAllocationPolicyVersion(
                DEFAULT_POLICY_ID,
                baseChunks,
                chunksPerEffectiveCitizen,
                Instant.EPOCH,
                "civiceconomy-default",
                "Conservative built-in Territory Free Allocation default",
                Instant.EPOCH,
                true);
    }

    public TerritoryFreeAllocationPolicy policy() {
        return new TerritoryFreeAllocationPolicy(baseChunks, chunksPerEffectiveCitizen);
    }
}
