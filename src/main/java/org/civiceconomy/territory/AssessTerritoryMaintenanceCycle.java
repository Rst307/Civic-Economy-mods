package org.civiceconomy.territory;

import java.time.Instant;
import java.util.List;
import org.civiceconomy.fiscal.ServiceIdentity;

public record AssessTerritoryMaintenanceCycle(
        ServiceIdentity serviceIdentity,
        String requestId,
        Instant startsAt,
        Instant endsAt,
        List<TerritoryMaintenanceClaimSnapshot> claims,
        String reason) {
    public AssessTerritoryMaintenanceCycle {
        if (serviceIdentity == null || startsAt == null || endsAt == null || claims == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance assessment cycle cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || !endsAt.isAfter(startsAt)
                || reason == null
                || reason.isBlank()
                || claims.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance assessment cycle values are invalid");
        }
        claims = List.copyOf(claims);
    }
}
