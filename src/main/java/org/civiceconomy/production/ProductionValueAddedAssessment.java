package org.civiceconomy.production;

import java.util.UUID;

public record ProductionValueAddedAssessment(
        UUID observationId,
        long outputReferenceValueMinorUnits,
        long inputReferenceValueMinorUnits,
        long valueAddedMinorUnits,
        ProductionValueAddedDecision decision,
        String reason) {
    public ProductionValueAddedAssessment {
        if (observationId == null || decision == null || reason == null || reason.isBlank()
                || outputReferenceValueMinorUnits < 0L
                || inputReferenceValueMinorUnits < 0L
                || valueAddedMinorUnits < 0L) {
            throw new IllegalArgumentException("Production Value Added assessment is invalid");
        }
    }
}
