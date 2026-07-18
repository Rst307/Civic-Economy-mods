package org.civiceconomy.production;

import java.util.List;
import org.civiceconomy.nation.NationId;

public record RollingProductionMarginalReturnAssessment(
        NationId nationId,
        long windowStartEpochMillis,
        long windowEndEpochMillis,
        long rawValueMinorUnits,
        long recencyWeightedValueMinorUnits,
        long afterFacilityReturnsMinorUnits,
        long finalValueMinorUnits,
        long facilityMarginalReductionMinorUnits,
        long industryMarginalReductionMinorUnits,
        int acceptedContributionCount,
        int unboundExportedObservationCount,
        List<RollingProductionContributionAssessment> contributions) {
    public RollingProductionMarginalReturnAssessment {
        if (nationId == null || windowStartEpochMillis < 0L
                || windowEndEpochMillis <= windowStartEpochMillis
                || rawValueMinorUnits < 0L || recencyWeightedValueMinorUnits < 0L
                || afterFacilityReturnsMinorUnits < 0L || finalValueMinorUnits < 0L
                || recencyWeightedValueMinorUnits > rawValueMinorUnits
                || afterFacilityReturnsMinorUnits > recencyWeightedValueMinorUnits
                || finalValueMinorUnits > afterFacilityReturnsMinorUnits
                || facilityMarginalReductionMinorUnits
                        != recencyWeightedValueMinorUnits - afterFacilityReturnsMinorUnits
                || industryMarginalReductionMinorUnits
                        != afterFacilityReturnsMinorUnits - finalValueMinorUnits
                || acceptedContributionCount < 0 || unboundExportedObservationCount < 0
                || contributions == null
                || contributions.stream().anyMatch(java.util.Objects::isNull)
                || acceptedContributionCount != contributions.size()) {
            throw new IllegalArgumentException(
                    "Rolling Production Marginal Return assessment is invalid");
        }
        contributions = List.copyOf(contributions);
    }

    public boolean healthy() {
        return unboundExportedObservationCount == 0;
    }
}
