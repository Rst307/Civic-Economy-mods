package org.civiceconomy.nation;

import java.util.UUID;

public record OnlineInterval(UUID intervalId, UUID playerId, long startedAtEpochMillis, long endedAtEpochMillis) {
    public OnlineInterval {
        if (intervalId == null || playerId == null) {
            throw new IllegalArgumentException("Online interval identity cannot be null");
        }
        if (startedAtEpochMillis < 0 || endedAtEpochMillis <= startedAtEpochMillis) {
            throw new IllegalArgumentException("Online interval must have a positive duration");
        }
    }

    public long durationMillis() {
        return Math.subtractExact(endedAtEpochMillis, startedAtEpochMillis);
    }
}
