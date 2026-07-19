package org.civiceconomy.strength;

import java.time.Duration;

public record AuditableEconomicActivityPolicy(
        Duration observationWindow,
        long fullStrengthScaleMinorUnits) {
    public AuditableEconomicActivityPolicy {
        if (observationWindow == null || observationWindow.isZero() || observationWindow.isNegative()
                || fullStrengthScaleMinorUnits <= 0L) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity policy values must be positive");
        }
    }
}
