package org.civiceconomy.strength;

public final class DiminishingStrengthNormalizer {
    private final double fullStrengthScale;

    public DiminishingStrengthNormalizer(double fullStrengthScale) {
        if (!Double.isFinite(fullStrengthScale) || fullStrengthScale <= 0D) {
            throw new IllegalArgumentException(
                    "National Strength full-strength scale must be positive");
        }
        this.fullStrengthScale = fullStrengthScale;
    }

    public int normalize(double observedValue) {
        if (!Double.isFinite(observedValue) || observedValue < 0D) {
            throw new IllegalArgumentException(
                    "National Strength observed value cannot be negative");
        }
        if (observedValue >= fullStrengthScale) {
            return 10_000;
        }
        return (int) Math.round(
                Math.sqrt((double) observedValue / (double) fullStrengthScale)
                        * 10_000D);
    }
}
