package org.civiceconomy.production;

public record FacilityAccountingStatus(
        RegisteredFacility facility,
        FacilityAccountingInterface accountingInterface,
        FacilityAccountingBaseline baseline) {
    public FacilityAccountingStatus {
        if (facility == null
                || (accountingInterface != null
                        && !facility.facilityId().equals(accountingInterface.facilityId()))
                || (baseline != null
                        && !facility.facilityId().equals(baseline.facilityId()))) {
            throw new IllegalArgumentException("Facility Accounting status is inconsistent");
        }
    }
}
