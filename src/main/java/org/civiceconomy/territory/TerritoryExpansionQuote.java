package org.civiceconomy.territory;

import java.time.Instant;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;

public record TerritoryExpansionQuote(
        NationId nationId,
        int currentClaimedChunks,
        int requestedChunks,
        int freeChunksInRequest,
        int chargeableChunks,
        MoneyAmount prepayment,
        Instant quotedAt) {
    public TerritoryExpansionQuote {
        if (nationId == null || prepayment == null || quotedAt == null) {
            throw new IllegalArgumentException("Territory Expansion Quote cannot contain null values");
        }
        if (currentClaimedChunks < 0
                || requestedChunks <= 0
                || freeChunksInRequest < 0
                || chargeableChunks < 0
                || freeChunksInRequest + chargeableChunks != requestedChunks) {
            throw new IllegalArgumentException("Invalid Territory Expansion Quote counts");
        }
    }
}
