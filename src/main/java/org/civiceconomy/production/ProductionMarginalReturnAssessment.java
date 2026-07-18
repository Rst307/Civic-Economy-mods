package org.civiceconomy.production;

import java.util.Map;
import java.util.UUID;

public record ProductionMarginalReturnAssessment(
        long rawValueMinorUnits,
        long afterFacilityReturnsMinorUnits,
        long finalValueMinorUnits,
        long facilityMarginalReductionMinorUnits,
        long industryMarginalReductionMinorUnits,
        Map<UUID, ProductionMarginalReturnBandAssessment> facilityAssessments,
        Map<ProductionIndustryId, ProductionMarginalReturnBandAssessment> industryAssessments) {
    public ProductionMarginalReturnAssessment {
        if (rawValueMinorUnits < 0L || afterFacilityReturnsMinorUnits < 0L
                || finalValueMinorUnits < 0L
                || afterFacilityReturnsMinorUnits > rawValueMinorUnits
                || finalValueMinorUnits > afterFacilityReturnsMinorUnits
                || facilityMarginalReductionMinorUnits
                        != rawValueMinorUnits - afterFacilityReturnsMinorUnits
                || industryMarginalReductionMinorUnits
                        != afterFacilityReturnsMinorUnits - finalValueMinorUnits
                || facilityAssessments == null || industryAssessments == null
                || facilityAssessments.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null || entry.getValue() == null)
                || industryAssessments.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException("Production marginal-return assessment is invalid");
        }
        facilityAssessments = Map.copyOf(facilityAssessments);
        industryAssessments = Map.copyOf(industryAssessments);
    }
}
