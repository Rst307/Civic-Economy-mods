package org.civiceconomy.territory;

import java.time.Instant;
import org.civiceconomy.fiscal.MoneyAmount;

public record TerritoryMaintenanceRestorationQuote(
        MoneyAmount totalDue, Instant cooldownEndsAt) {
    public TerritoryMaintenanceRestorationQuote {
        if (totalDue == null || cooldownEndsAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration quote cannot contain null values");
        }
    }
}
