package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredCitizenship(
        UUID citizenshipId,
        UUID playerId,
        UUID nationId,
        long joinedAtEpochMillis,
        Long endedAtEpochMillis,
        String joinServiceIdentity,
        String joinRequestId,
        String leaveServiceIdentity,
        String leaveRequestId) {}
