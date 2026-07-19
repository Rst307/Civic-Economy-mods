package org.civiceconomy.nation;

import java.time.Duration;

public record EffectiveCitizenPopulationPolicy(
        Duration observationWindow, Duration fullContributionTime) {
    public EffectiveCitizenPopulationPolicy {
        if (observationWindow == null || observationWindow.isZero() || observationWindow.isNegative()) {
            throw new IllegalArgumentException("Effective Citizen observation window must be positive");
        }
        if (fullContributionTime == null || fullContributionTime.isZero()
                || fullContributionTime.isNegative()) {
            throw new IllegalArgumentException("Effective Citizen full contribution time must be positive");
        }
    }
}
