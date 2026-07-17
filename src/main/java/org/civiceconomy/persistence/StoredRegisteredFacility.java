package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredRegisteredFacility(
        UUID facilityId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID ftbTeamId,
        String dimensionId,
        int coreBlockX,
        int coreBlockY,
        int coreBlockZ,
        UUID actorPlayerId,
        String state,
        String reason,
        long registeredAtEpochMillis) {}
