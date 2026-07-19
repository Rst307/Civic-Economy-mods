package org.civiceconomy.production;

import java.util.HashSet;
import java.util.List;

public record FacilityAccountingBaselineSnapshot(
        String createVersion,
        List<FacilityBaselineMachine> machines,
        List<MachineInventoryChange> startingInventory) {
    public FacilityAccountingBaselineSnapshot {
        if (createVersion == null || createVersion.isBlank()
                || machines == null || machines.isEmpty()
                || machines.stream().anyMatch(java.util.Objects::isNull)
                || startingInventory == null
                || startingInventory.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(machines.stream()
                                .map(FacilityBaselineMachine::position)
                                .toList())
                        .size() != machines.size()
                || new HashSet<>(startingInventory.stream()
                                .map(MachineInventoryChange::slot)
                                .toList())
                        .size() != startingInventory.size()
                || startingInventory.stream().anyMatch(change -> change.stack().isEmpty())) {
            throw new IllegalArgumentException("Facility Accounting Baseline snapshot is invalid");
        }
        machines = List.copyOf(machines);
        startingInventory = List.copyOf(startingInventory);
    }
}
