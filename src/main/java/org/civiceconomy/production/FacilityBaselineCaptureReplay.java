package org.civiceconomy.production;

public record FacilityBaselineCaptureReplay(FacilityAccountingBaseline baseline)
        implements FacilityBaselineCapturePreparation {
    public FacilityBaselineCaptureReplay {
        if (baseline == null) {
            throw new IllegalArgumentException(
                    "Facility Baseline capture replay is required");
        }
    }
}
