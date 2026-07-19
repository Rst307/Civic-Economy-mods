package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryClaimPermit(
        UUID permitId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        int quotedCurrentClaimedChunks,
        int quotedFreeAllocation,
        long prepaymentMinorUnits,
        UUID prepaymentTransactionId,
        String state,
        long issuedAtEpochMillis,
        long expiresAtEpochMillis) {}
