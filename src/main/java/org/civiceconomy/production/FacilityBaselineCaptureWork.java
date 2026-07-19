package org.civiceconomy.production;

public record FacilityBaselineCaptureWork(
        CaptureFacilityAccountingBaseline request,
        RegisteredFacility facility,
        FacilityAccountingInterface accountingInterface)
        implements FacilityBaselineCapturePreparation {
    public FacilityBaselineCaptureWork {
        if (request == null || facility == null || accountingInterface == null
                || !request.facilityId().equals(facility.facilityId())
                || !facility.facilityId().equals(accountingInterface.facilityId())) {
            throw new IllegalArgumentException(
                    "Facility Baseline capture work is inconsistent");
        }
    }
}
