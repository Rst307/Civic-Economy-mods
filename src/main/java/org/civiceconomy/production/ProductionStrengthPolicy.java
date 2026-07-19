package org.civiceconomy.production;

import java.time.Duration;

public record ProductionStrengthPolicy(
        Duration observationWindow,
        Duration fullWeightWindow,
        long fullStrengthScaleMinorUnits) {
    public ProductionStrengthPolicy {
        if (observationWindow == null || fullWeightWindow == null
                || observationWindow.isNegative() || observationWindow.isZero()
                || fullWeightWindow.isNegative() || fullWeightWindow.isZero()
                || fullWeightWindow.compareTo(observationWindow) > 0
                || fullStrengthScaleMinorUnits <= 0L
                || !isExactPositiveMillis(observationWindow)
                || !isExactPositiveMillis(fullWeightWindow)) {
            throw new IllegalArgumentException("Production Strength policy is invalid");
        }
    }

    public long observationWindowMillis() {
        return observationWindow.toMillis();
    }

    public long fullWeightWindowMillis() {
        return fullWeightWindow.toMillis();
    }

    private static boolean isExactPositiveMillis(Duration duration) {
        try {
            long millis = duration.toMillis();
            return millis > 0L && Duration.ofMillis(millis).equals(duration);
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
