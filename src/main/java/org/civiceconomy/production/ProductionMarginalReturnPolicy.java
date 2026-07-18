package org.civiceconomy.production;

public record ProductionMarginalReturnPolicy(
        long facilitySoftCapMinorUnits,
        int facilityExcessWeightBasisPoints,
        long industrySoftCapMinorUnits,
        int industryExcessWeightBasisPoints) {
    public ProductionMarginalReturnPolicy {
        if (facilitySoftCapMinorUnits <= 0L || industrySoftCapMinorUnits <= 0L
                || facilityExcessWeightBasisPoints < 0
                || facilityExcessWeightBasisPoints > 10_000
                || industryExcessWeightBasisPoints < 0
                || industryExcessWeightBasisPoints > 10_000) {
            throw new IllegalArgumentException("Production marginal-return policy is invalid");
        }
    }
}
