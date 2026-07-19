package org.civiceconomy.territory;

import java.time.Instant;
import org.civiceconomy.fiscal.MoneyAmount;

public final class TerritoryExpansionPricingPolicy {
    private final long firstOverageChunkCost;
    private final long additionalMarginalCost;

    public TerritoryExpansionPricingPolicy(
            long firstOverageChunkCost, long additionalMarginalCost) {
        if (firstOverageChunkCost < 0L || additionalMarginalCost < 0L) {
            throw new IllegalArgumentException("Territory Expansion pricing cannot be negative");
        }
        this.firstOverageChunkCost = firstOverageChunkCost;
        this.additionalMarginalCost = additionalMarginalCost;
    }

    public TerritoryExpansionQuote quote(
            TerritoryFreeAllocation allocation,
            int currentClaimedChunks,
            int requestedChunks,
            Instant quotedAt) {
        if (allocation == null || quotedAt == null) {
            throw new IllegalArgumentException("Territory Expansion quote cannot contain null values");
        }
        if (currentClaimedChunks < 0 || requestedChunks <= 0) {
            throw new IllegalArgumentException("Territory Expansion claim counts are invalid");
        }
        int resultingClaims = Math.addExact(currentClaimedChunks, requestedChunks);
        int firstChargeableOrdinal = Math.max(
                1, Math.addExact(currentClaimedChunks, 1) - allocation.totalFreeChunks());
        int finalChargeableOrdinal = resultingClaims - allocation.totalFreeChunks();
        int chargeableChunks = finalChargeableOrdinal < firstChargeableOrdinal
                ? 0
                : Math.addExact(finalChargeableOrdinal - firstChargeableOrdinal, 1);
        int freeChunks = requestedChunks - chargeableChunks;
        long prepayment = chargeableChunks == 0
                ? 0L
                : totalMarginalCost(firstChargeableOrdinal, finalChargeableOrdinal);
        return new TerritoryExpansionQuote(
                allocation.nationId(),
                currentClaimedChunks,
                requestedChunks,
                freeChunks,
                chargeableChunks,
                MoneyAmount.ofMinorUnits(prepayment),
                quotedAt);
    }

    private long totalMarginalCost(int firstOrdinal, int finalOrdinal) {
        long count = (long) finalOrdinal - firstOrdinal + 1L;
        long baseCost = Math.multiplyExact(count, firstOverageChunkCost);
        long firstStep = firstOrdinal - 1L;
        long finalStep = finalOrdinal - 1L;
        long stepSum = arithmeticSeries(count, firstStep, finalStep);
        return Math.addExact(
                baseCost, Math.multiplyExact(stepSum, additionalMarginalCost));
    }

    private static long arithmeticSeries(long count, long first, long last) {
        long endpoints = Math.addExact(first, last);
        if ((count & 1L) == 0L) {
            return Math.multiplyExact(count / 2L, endpoints);
        }
        return Math.multiplyExact(count, endpoints / 2L);
    }
}
