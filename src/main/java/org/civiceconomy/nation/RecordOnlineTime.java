package org.civiceconomy.nation;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record RecordOnlineTime(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID playerId,
        long startedAtEpochMillis,
        long endedAtEpochMillis) {
    public RecordOnlineTime {
        if (serviceIdentity == null || playerId == null) {
            throw new IllegalArgumentException("Online-time request identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (startedAtEpochMillis < 0 || endedAtEpochMillis <= startedAtEpochMillis) {
            throw new IllegalArgumentException("Online-time request must have a positive duration");
        }
    }
}
