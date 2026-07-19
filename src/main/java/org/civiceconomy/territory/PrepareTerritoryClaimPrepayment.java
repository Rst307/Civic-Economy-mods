package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record PrepareTerritoryClaimPrepayment(
        String requestId,
        NationId nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        int quotedFreeAllocation,
        TerritoryExpansionQuote quote,
        Instant expiresAt) {
    public PrepareTerritoryClaimPrepayment {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim prepayment request ID cannot be blank");
        }
        if (nationId == null
                || ftbTeamId == null
                || actorPlayerId == null
                || quote == null
                || expiresAt == null) {
            throw new IllegalArgumentException("Territory Claim prepayment cannot contain null values");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim prepayment dimension cannot be blank");
        }
        if (quotedFreeAllocation < 0
                || !quote.nationId().equals(nationId)
                || quote.requestedChunks() != 1
                || quote.prepayment().minorUnits() <= 0L) {
            throw new IllegalArgumentException("Territory Claim prepayment quote is invalid");
        }
    }
}
