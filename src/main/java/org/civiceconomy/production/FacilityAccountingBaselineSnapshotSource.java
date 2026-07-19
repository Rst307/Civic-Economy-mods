package org.civiceconomy.production;

@FunctionalInterface
public interface FacilityAccountingBaselineSnapshotSource {
    FacilityAccountingBaselineSnapshot capture(
            RegisteredFacility facility,
            FacilityAccountingInterface accountingInterface);
}
