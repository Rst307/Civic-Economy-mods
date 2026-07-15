package org.civiceconomy.territory;

import java.time.Instant;

public final class TerritoryMaintenanceRestorationCooldownException
        extends IllegalStateException {
    private final Instant eligibleAt;

    public TerritoryMaintenanceRestorationCooldownException(Instant eligibleAt) {
        super("Territory Maintenance Restoration is cooling down until " + eligibleAt);
        if (eligibleAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration eligibility cannot be null");
        }
        this.eligibleAt = eligibleAt;
    }

    public Instant eligibleAt() {
        return eligibleAt;
    }
}
