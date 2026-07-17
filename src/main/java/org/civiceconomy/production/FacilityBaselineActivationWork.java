package org.civiceconomy.production;

public record FacilityBaselineActivationWork(
        ActivateFacilityAccountingBaseline request,
        RegisteredFacility facility,
        FacilityAccountingInterface accountingInterface)
        implements FacilityBaselineActivationPreparation {
    public FacilityBaselineActivationWork {
        if (request == null || facility == null || accountingInterface == null
                || !request.facilityId().equals(facility.facilityId())
                || !facility.facilityId().equals(accountingInterface.facilityId())) {
            throw new IllegalArgumentException(
                    "Facility Baseline activation work is inconsistent");
        }
    }
}
