package org.civiceconomy.production;

import java.util.UUID;

public record FacilityProductionDecision(
        UUID observationId,
        UUID facilityId,
        UUID interfaceId,
        UUID receiptId,
        FacilityProductionDecisionKind kind,
        String reason) {
    public FacilityProductionDecision {
        if (observationId == null || kind == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Facility Production Decision is invalid");
        }
    }
}
