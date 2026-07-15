package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredMonetaryStockCorrection(
        UUID correctionId,
        String administratorIdentity,
        String requestId,
        UUID incidentId,
        UUID operationId,
        UUID batchId,
        long amountMinorUnits,
        String evidenceReference,
        String reason,
        UUID eventId,
        long correctedAtEpochMillis) {}
