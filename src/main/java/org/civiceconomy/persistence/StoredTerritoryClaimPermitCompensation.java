package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryClaimPermitCompensation(
        UUID compensationId,
        UUID permitId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        String kind,
        String reason,
        String refundRequestId,
        UUID refundTransactionId,
        long requestedAtEpochMillis,
        Long completedAtEpochMillis) {}
