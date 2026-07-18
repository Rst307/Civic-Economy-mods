package org.civiceconomy.production;

import java.util.Map;

public record ProductionChainContributionAssessment(
        long acceptedValueMinorUnits,
        int acceptedObservationCount,
        int excludedObservationCount,
        int sourceReceiptCount,
        int unmatchedSourceReceiptCount,
        Map<ProductionValueAddedDecision, Integer> excludedByDecision) {
    public ProductionChainContributionAssessment {
        if (acceptedValueMinorUnits < 0L
                || acceptedObservationCount < 0
                || excludedObservationCount < 0
                || sourceReceiptCount < 0
                || unmatchedSourceReceiptCount < 0
                || unmatchedSourceReceiptCount > sourceReceiptCount
                || excludedByDecision == null
                || excludedByDecision.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("Production chain contribution assessment is invalid");
        }
        excludedByDecision = Map.copyOf(excludedByDecision);
    }
}
