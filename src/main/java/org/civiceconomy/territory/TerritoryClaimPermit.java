package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;

public record TerritoryClaimPermit(
        UUID permitId,
        NationId nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        int quotedCurrentClaimedChunks,
        int quotedFreeAllocation,
        MoneyAmount prepayment,
        UUID prepaymentTransactionId,
        TerritoryClaimPermitState state,
        Instant issuedAt,
        Instant expiresAt) {
    public TerritoryClaimPermit {
        if (permitId == null
                || nationId == null
                || ftbTeamId == null
                || actorPlayerId == null
                || dimensionId == null
                || prepayment == null
                || prepaymentTransactionId == null
                || state == null
                || issuedAt == null
                || expiresAt == null) {
            throw new IllegalArgumentException("Territory Claim Permit cannot contain null values");
        }
        if (dimensionId.isBlank()
                || quotedCurrentClaimedChunks < 0
                || quotedFreeAllocation < 0
                || prepayment.minorUnits() <= 0L
                || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Territory Claim Permit values are invalid");
        }
    }
}
