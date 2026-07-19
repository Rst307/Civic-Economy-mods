package org.civiceconomy.territory;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record OpenTerritoryMaintenanceCycle(
        ServiceIdentity serviceIdentity, String requestId, Instant startsAt, Instant endsAt) {
    public OpenTerritoryMaintenanceCycle {
        if (serviceIdentity == null || startsAt == null || endsAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Cycle request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Cycle request ID cannot be blank");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Cycle must have a positive duration");
        }
    }
}
