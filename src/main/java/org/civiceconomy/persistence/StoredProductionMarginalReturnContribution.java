package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredProductionMarginalReturnContribution(
        UUID observationId,
        UUID anchorExportId,
        UUID facilityId,
        UUID industryAssignmentId,
        UUID marginalReturnPolicyId,
        long valueAddedMinorUnits,
        long evidenceAtEpochMillis,
        long recordedAtEpochMillis) {}
