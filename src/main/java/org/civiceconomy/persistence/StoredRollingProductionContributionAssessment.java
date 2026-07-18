package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredRollingProductionContributionAssessment(
        UUID nationId,
        UUID observationId,
        UUID anchorExportId,
        UUID facilityId,
        String industryId,
        UUID industryAssignmentId,
        UUID marginalReturnPolicyId,
        long evidenceAtEpochMillis,
        int recencyWeightBasisPoints,
        long rawValueMinorUnits,
        long recencyWeightedValueMinorUnits,
        long facilityUsageBeforeMinorUnits,
        long afterFacilityReturnsMinorUnits,
        long industryUsageBeforeMinorUnits,
        long finalValueMinorUnits) {}
