package org.civiceconomy.territory;

import java.time.Instant;
import java.util.Optional;

public record TerritoryMaintenanceRestorationHistory(
        boolean previouslySuspended, Optional<Instant> cooldownEndsAt) {
    public TerritoryMaintenanceRestorationHistory {
        if (cooldownEndsAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration history cannot contain null values");
        }
    }
}
