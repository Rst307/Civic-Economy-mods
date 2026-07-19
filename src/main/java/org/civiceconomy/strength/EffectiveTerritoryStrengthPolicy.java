package org.civiceconomy.strength;

public record EffectiveTerritoryStrengthPolicy(
        int fullStrengthScaleEffectiveClaims) {
    public EffectiveTerritoryStrengthPolicy {
        if (fullStrengthScaleEffectiveClaims <= 0) {
            throw new IllegalArgumentException(
                    "Effective Territory Strength policy scale must be positive");
        }
    }
}
