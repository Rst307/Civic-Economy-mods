package org.civiceconomy.production;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProductionValueAddedWindow {
    private static final int FULL_WEIGHT_BASIS_POINTS = 10_000;

    private final ProductionValueAddedCalculator calculator;
    private final long windowMillis;
    private final long fullWeightMillis;

    public ProductionValueAddedWindow(
            ProductionValueAddedCalculator calculator,
            Duration window,
            Duration fullWeightWindow) {
        if (calculator == null || window == null || fullWeightWindow == null
                || window.isZero() || window.isNegative()
                || fullWeightWindow.isZero() || fullWeightWindow.isNegative()
                || fullWeightWindow.compareTo(window) > 0) {
            throw new IllegalArgumentException("Production Value Added window settings are invalid");
        }
        this.calculator = calculator;
        this.windowMillis = window.toMillis();
        this.fullWeightMillis = fullWeightWindow.toMillis();
        if (windowMillis <= 0L || fullWeightMillis <= 0L || fullWeightMillis > windowMillis) {
            throw new IllegalArgumentException("Production Value Added window durations are invalid");
        }
    }

    public ProductionValueAddedWindowAssessment assess(
            List<FacilityProductionObservation> observations, Instant asOf) {
        if (observations == null || observations.stream().anyMatch(java.util.Objects::isNull)
                || asOf == null) {
            throw new IllegalArgumentException("Production observations and time are required");
        }
        long end = asOf.toEpochMilli();
        if (end < 0L) {
            throw new IllegalArgumentException("Production window end must be nonnegative");
        }
        long start = windowStart(end);

        Map<UUID, FacilityProductionObservation> unique = new LinkedHashMap<>();
        for (FacilityProductionObservation observation : observations) {
            UUID id = observation.completion().observationId();
            FacilityProductionObservation previous = unique.putIfAbsent(id, observation);
            if (previous != null && !previous.equals(observation)) {
                throw new IllegalStateException(
                        "Production observation replay changed immutable observation " + id);
            }
        }

        long acceptedValue = 0L;
        long weightedValue = 0L;
        int acceptedCount = 0;
        int excludedCount = 0;
        EnumMap<ProductionValueAddedDecision, Integer> excludedByDecision =
                new EnumMap<>(ProductionValueAddedDecision.class);
        for (FacilityProductionObservation observation : unique.values()) {
            long observedAt = observation.completion().observedAtEpochMillis();
            if (observedAt < start || observedAt >= end) {
                continue;
            }
            ProductionValueAddedAssessment assessment = calculator.calculate(observation);
            if (assessment.decision() != ProductionValueAddedDecision.INCLUDED) {
                excludedCount++;
                excludedByDecision.merge(assessment.decision(), 1, Integer::sum);
                continue;
            }
            int weight = weightBasisPoints(end - observedAt);
            try {
                acceptedValue = Math.addExact(acceptedValue, assessment.valueAddedMinorUnits());
                weightedValue = Math.addExact(
                        weightedValue,
                        Math.multiplyExact(assessment.valueAddedMinorUnits(), weight)
                                / FULL_WEIGHT_BASIS_POINTS);
                acceptedCount++;
            } catch (ArithmeticException overflow) {
                excludedCount++;
                excludedByDecision.merge(
                        ProductionValueAddedDecision.EXCLUDED_WEIGHT_OVERFLOW, 1, Integer::sum);
            }
        }
        return new ProductionValueAddedWindowAssessment(
                start, end, acceptedValue, weightedValue, acceptedCount,
                excludedCount, excludedByDecision);
    }

    public ProductionValueAddedWindowAssessment assess(
            FacilityProductionObservationRegistry source, Instant asOf) {
        if (source == null || asOf == null) {
            throw new IllegalArgumentException("Production observation source and time are required");
        }
        long end = asOf.toEpochMilli();
        if (end < 0L) {
            throw new IllegalArgumentException("Production window end must be nonnegative");
        }
        return assess(source.observations(windowStart(end), end), asOf);
    }

    private long windowStart(long end) {
        long start;
        try {
            start = Math.subtractExact(end, windowMillis);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Production window start overflow", overflow);
        }
        return Math.max(0L, start);
    }

    private int weightBasisPoints(long ageMillis) {
        if (ageMillis <= fullWeightMillis) {
            return FULL_WEIGHT_BASIS_POINTS;
        }
        long decayingMillis = windowMillis - fullWeightMillis;
        long remainingMillis = Math.max(0L, windowMillis - ageMillis);
        return (int) Math.min(
                FULL_WEIGHT_BASIS_POINTS,
                Math.multiplyExact(remainingMillis, FULL_WEIGHT_BASIS_POINTS)
                        / decayingMillis);
    }
}
