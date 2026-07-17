package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFacilityProductionDecision(
        UUID observationId,
        UUID facilityId,
        UUID interfaceId,
        UUID receiptId,
        String decision,
        String reason,
        long decidedAtEpochMillis) {}
