package org.civiceconomy.production;

public record FacilityBaselineMachine(
        CreateMachineKind machineKind, FacilityMachinePosition position) {
    public FacilityBaselineMachine {
        if (machineKind == null || position == null) {
            throw new IllegalArgumentException("Facility Baseline machine is invalid");
        }
    }
}
