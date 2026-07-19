package org.civiceconomy.territory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.civiceconomy.fiscal.MoneyAmount;

public record TerritoryMaintenanceRestorationPolicy(
        MoneyAmount restorationFee, Duration cooldown) {
    public TerritoryMaintenanceRestorationPolicy {
        if (restorationFee == null || cooldown == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration policy cannot contain null values");
        }
        if (cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration cooldown must be positive");
        }
    }

    public TerritoryMaintenanceRestorationQuote quote(
            MoneyAmount nextFullCycleMaintenance,
            Optional<Instant> lastRestoredAt,
            Instant restoredAt) {
        if (nextFullCycleMaintenance == null
                || lastRestoredAt == null
                || restoredAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration quote cannot contain null values");
        }
        lastRestoredAt.ifPresent(previous -> {
            Instant eligibleAt = previous.plus(cooldown);
            if (restoredAt.isBefore(eligibleAt)) {
                throw new TerritoryMaintenanceRestorationCooldownException(eligibleAt);
            }
        });
        return new TerritoryMaintenanceRestorationQuote(
                restorationFee.plus(nextFullCycleMaintenance),
                restoredAt.plus(cooldown));
    }
}
