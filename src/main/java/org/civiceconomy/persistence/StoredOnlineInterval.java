package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredOnlineInterval(
        UUID intervalId,
        String serviceIdentity,
        String requestId,
        UUID playerId,
        long startedAtEpochMillis,
        long endedAtEpochMillis) {}
