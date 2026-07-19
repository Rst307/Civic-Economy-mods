package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record IssueTerritoryClaimPermit(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        int quotedCurrentClaimedChunks,
        int quotedFreeAllocation,
        long prepaymentMinorUnits,
        UUID prepaymentTransactionId,
        Instant expiresAt) {
    public IssueTerritoryClaimPermit {
        if (serviceIdentity == null
                || nationId == null
                || ftbTeamId == null
                || actorPlayerId == null
                || prepaymentTransactionId == null
                || expiresAt == null) {
            throw new IllegalArgumentException("Territory Claim Permit request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim Permit request ID cannot be blank");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim Permit dimension cannot be blank");
        }
        if (quotedCurrentClaimedChunks < 0
                || quotedFreeAllocation < 0
                || prepaymentMinorUnits <= 0L) {
            throw new IllegalArgumentException("Territory Claim Permit quote values are invalid");
        }
    }
}
