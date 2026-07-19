package org.civiceconomy.territory;

import java.time.Instant;

public record TerritoryMaintenanceCycleWindow(
        String requestId, Instant startsAt, Instant endsAt) {
    public TerritoryMaintenanceCycleWindow {
        if (requestId == null || requestId.isBlank() || startsAt == null || endsAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Cycle Window cannot contain invalid values");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Cycle Window must have positive duration");
        }
    }
}
