package org.civiceconomy.nation;

import java.util.UUID;

public final class OverlappingOnlineIntervalException extends IllegalStateException {
    public OverlappingOnlineIntervalException(UUID playerId, OnlineInterval existing) {
        super("Observed online time for player " + playerId + " overlaps interval " + existing.intervalId());
    }
}
