package org.civiceconomy.production;

import java.time.Instant;

public record FacilityProductionObservation(
        CreateRecipeCompletion completion,
        FacilityAccountingReceipt receipt,
        FacilityProductionDecision decision,
        Instant decidedAt) {
    public FacilityProductionObservation {
        if (completion == null || receipt == null || decision == null || decidedAt == null
                || !completion.observationId().equals(decision.observationId())
                || !receipt.receiptId().equals(decision.receiptId())) {
            throw new IllegalArgumentException("Facility Production Observation is invalid");
        }
    }
}
