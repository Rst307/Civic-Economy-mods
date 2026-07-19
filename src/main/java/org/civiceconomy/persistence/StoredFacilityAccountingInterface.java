package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFacilityAccountingInterface(
        UUID interfaceId,
        String serviceIdentity,
        String requestId,
        UUID facilityId,
        String dimensionId,
        int blockX,
        int blockY,
        int blockZ,
        UUID actorPlayerId,
        String reason,
        long registeredAtEpochMillis) {}
