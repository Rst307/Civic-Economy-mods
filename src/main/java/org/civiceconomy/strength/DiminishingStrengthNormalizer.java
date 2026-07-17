package org.civiceconomy.strength;

public final class DiminishingStrengthNormalizer {
    private final long fullStrengthScale;

    public DiminishingStrengthNormalizer(long fullStrengthScale) {
        if (fullStrengthScale <= 0L) {
            throw new IllegalArgumentException(
                    "National Strength full-strength scale must be positive");
        }
        this.fullStrengthScale = fullStrengthScale;
    }

    public int normalize(long observedValue) {
        if (observedValue < 0L) {
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
