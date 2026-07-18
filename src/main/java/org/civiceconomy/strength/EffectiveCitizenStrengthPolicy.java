package org.civiceconomy.strength;

public record EffectiveCitizenStrengthPolicy(
        int fullStrengthScaleCitizenEquivalents) {
    public EffectiveCitizenStrengthPolicy {
        if (fullStrengthScaleCitizenEquivalents <= 0) {
            throw new IllegalArgumentException(
                    "Effective Citizen Strength policy scale must be positive");
        }
    }
}
