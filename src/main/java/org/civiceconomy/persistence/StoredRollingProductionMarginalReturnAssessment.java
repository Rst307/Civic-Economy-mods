package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredRollingProductionMarginalReturnAssessment(
        UUID nationId,
        long windowStartEpochMillis,
        long windowEndEpochMillis,
        long rawValueMinorUnits,
        long recencyWeightedValueMinorUnits,
        long afterFacilityReturnsMinorUnits,
        long finalValueMinorUnits,
        long facilityMarginalReductionMinorUnits,
        long industryMarginalReductionMinorUnits,
        int acceptedContributionCount,
        int unboundExportedObservationCount) {}
