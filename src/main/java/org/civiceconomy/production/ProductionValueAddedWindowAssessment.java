package org.civiceconomy.production;

import java.util.Map;

public record ProductionValueAddedWindowAssessment(
        long windowStartEpochMillis,
        long windowEndEpochMillis,
        long acceptedValueMinorUnits,
        long weightedValueMinorUnits,
        int acceptedObservationCount,
        int excludedObservationCount,
        Map<ProductionValueAddedDecision, Integer> excludedByDecision) {
    public ProductionValueAddedWindowAssessment {
        if (windowStartEpochMillis < 0L
                || windowEndEpochMillis <= windowStartEpochMillis
                || acceptedValueMinorUnits < 0L
                || weightedValueMinorUnits < 0L
                || acceptedObservationCount < 0
                || excludedObservationCount < 0
                || excludedByDecision == null
                || excludedByDecision.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("Production Value Added window is invalid");
        }
        excludedByDecision = Map.copyOf(excludedByDecision);
    }
}
