package org.civiceconomy.production;

public record FacilityBaselineActivationReplay(FacilityAccountingBaseline baseline)
        implements FacilityBaselineActivationPreparation {
    public FacilityBaselineActivationReplay {
        if (baseline == null) {
            throw new IllegalArgumentException(
                    "Facility Baseline activation replay is required");
        }
    }
}
