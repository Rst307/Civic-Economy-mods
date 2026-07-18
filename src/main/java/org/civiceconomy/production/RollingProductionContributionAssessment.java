package org.civiceconomy.production;

import java.time.Instant;
import java.util.UUID;

public record RollingProductionContributionAssessment(
        UUID observationId,
        UUID anchorExportId,
        UUID facilityId,
        ProductionIndustryId industryId,
        UUID industryAssignmentId,
        UUID marginalReturnPolicyId,
        Instant evidenceAt,
        int recencyWeightBasisPoints,
        long rawValueMinorUnits,
        long recencyWeightedValueMinorUnits,
        long facilityUsageBeforeMinorUnits,
        long afterFacilityReturnsMinorUnits,
        long industryUsageBeforeMinorUnits,
        long finalValueMinorUnits) {
    public RollingProductionContributionAssessment {
        if (observationId == null || anchorExportId == null || facilityId == null
                || industryId == null || industryAssignmentId == null
                || marginalReturnPolicyId == null || evidenceAt == null
                || recencyWeightBasisPoints < 0 || recencyWeightBasisPoints > 10_000
                || rawValueMinorUnits <= 0L || recencyWeightedValueMinorUnits < 0L
                || recencyWeightedValueMinorUnits > rawValueMinorUnits
                || facilityUsageBeforeMinorUnits < 0L
                || afterFacilityReturnsMinorUnits < 0L
                || afterFacilityReturnsMinorUnits > recencyWeightedValueMinorUnits
                || industryUsageBeforeMinorUnits < 0L || finalValueMinorUnits < 0L
                || finalValueMinorUnits > afterFacilityReturnsMinorUnits) {
            throw new IllegalArgumentException(
                    "Rolling production contribution assessment is invalid");
        }
    }
}
