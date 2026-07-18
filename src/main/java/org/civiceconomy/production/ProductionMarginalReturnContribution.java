package org.civiceconomy.production;

import java.util.UUID;

public record ProductionMarginalReturnContribution(
        UUID observationId,
        UUID facilityId,
        ProductionIndustryId industryId,
        long valueMinorUnits) {
    public ProductionMarginalReturnContribution {
        if (observationId == null || facilityId == null || industryId == null
                || valueMinorUnits <= 0L) {
            throw new IllegalArgumentException("Production marginal-return contribution is invalid");
        }
    }
}
