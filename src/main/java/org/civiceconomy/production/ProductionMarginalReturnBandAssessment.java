package org.civiceconomy.production;

public record ProductionMarginalReturnBandAssessment(
        long rawValueMinorUnits,
        long adjustedValueMinorUnits) {
    public ProductionMarginalReturnBandAssessment {
        if (rawValueMinorUnits < 0L || adjustedValueMinorUnits < 0L
                || adjustedValueMinorUnits > rawValueMinorUnits) {
            throw new IllegalArgumentException("Production marginal-return band is invalid");
        }
    }

    public long reductionMinorUnits() {
        return rawValueMinorUnits - adjustedValueMinorUnits;
    }
}
