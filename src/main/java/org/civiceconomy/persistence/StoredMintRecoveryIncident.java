package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredMintRecoveryIncident(
        UUID incidentId,
        UUID operationId,
        UUID batchId,
        String step,
        String state,
        String failureKind,
        String failureMessage,
        long firstObservedAtEpochMillis,
        long lastObservedAtEpochMillis,
        long occurrenceCount,
        String resolutionKind,
        String resolutionDetail,
        Long resolvedAtEpochMillis) {}
