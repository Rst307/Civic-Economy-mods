package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record TerritoryMaintenanceCycle(
        UUID cycleId,
        ServiceIdentity serviceIdentity,
        String requestId,
        Instant startsAt,
        Instant endsAt,
        Instant openedAt) {
    public TerritoryMaintenanceCycle {
        if (cycleId == null
                || serviceIdentity == null
                || startsAt == null
                || endsAt == null
                || openedAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Cycle cannot contain null values");
        }
        if (requestId == null || requestId.isBlank() || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Territory Maintenance Cycle values are invalid");
        }
    }
}
