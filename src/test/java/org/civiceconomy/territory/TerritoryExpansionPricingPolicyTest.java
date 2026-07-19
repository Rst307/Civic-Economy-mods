package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class TerritoryExpansionPricingPolicyTest {
    @Test
    void quotesOnlyChunksBeyondFreeAllocationWithConvexMarginalCost() {
        NationId nationId = new NationId(
                UUID.fromString("97834dc0-bf4c-42d8-b278-fe246b61566f"));
        TerritoryFreeAllocation allocation = new TerritoryFreeAllocation(
                nationId, 9, 2, 4, 17);
        TerritoryExpansionPricingPolicy pricing =
                new TerritoryExpansionPricingPolicy(100L, 50L);

        TerritoryExpansionQuote quote = pricing.quote(
                allocation, 16, 3, Instant.parse("2026-07-14T12:00:00Z"));

        assertEquals(nationId, quote.nationId());
        assertEquals(16, quote.currentClaimedChunks());
        assertEquals(3, quote.requestedChunks());
        assertEquals(1, quote.freeChunksInRequest());
        assertEquals(2, quote.chargeableChunks());
        assertEquals(250L, quote.prepayment().minorUnits());
    }
}
