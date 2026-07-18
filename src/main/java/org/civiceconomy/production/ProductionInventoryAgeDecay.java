package org.civiceconomy.production;

import java.time.Duration;

public final class ProductionInventoryAgeDecay {
    private static final int FULL_WEIGHT_BASIS_POINTS = 10_000;
    private final long fullContributionMillis;
    private final long expiryMillis;

    public ProductionInventoryAgeDecay(Duration fullContribution, Duration expiry) {
        if (fullContribution == null || expiry == null
                || fullContribution.isZero() || fullContribution.isNegative()
                || expiry.isZero() || expiry.isNegative()
                || fullContribution.compareTo(expiry) > 0) {
            throw new IllegalArgumentException("Production inventory age settings are invalid");
        }
        this.fullContributionMillis = fullContribution.toMillis();
        this.expiryMillis = expiry.toMillis();
        if (fullContributionMillis <= 0L || expiryMillis <= 0L
                || fullContributionMillis > expiryMillis) {
            throw new IllegalArgumentException("Production inventory age durations are invalid");
        }
    }

    public int basisPoints(ProductionInventoryAge age, long asOfEpochMillis) {
        if (age == null || asOfEpochMillis < age.firstObservedAtEpochMillis()) {
            throw new IllegalArgumentException("Production inventory age query is invalid");
        }
        long elapsed = asOfEpochMillis - age.firstObservedAtEpochMillis();
        if (elapsed <= fullContributionMillis) {
            return FULL_WEIGHT_BASIS_POINTS;
        }
        if (elapsed >= expiryMillis) {
            return 0;
        }
        long decayRange = expiryMillis - fullContributionMillis;
        long remaining = expiryMillis - elapsed;
        return (int) Math.min(
                FULL_WEIGHT_BASIS_POINTS,
                Math.multiplyExact(remaining, FULL_WEIGHT_BASIS_POINTS) / decayRange);
    }
}
