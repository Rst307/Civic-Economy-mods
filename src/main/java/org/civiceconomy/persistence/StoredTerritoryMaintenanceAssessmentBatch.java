package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryMaintenanceAssessmentBatch(
        UUID cycleId,
        String serviceIdentity,
        String requestId,
        int claimCount,
        String snapshotSha256,
        long recordedAtEpochMillis) {}
