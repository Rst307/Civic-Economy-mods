package org.civiceconomy.production;

public record MachineInventoryChange(int slot, ProductionStack stack) {
    public MachineInventoryChange {
        if (slot < 0 || stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Machine inventory change is invalid");
        }
    }
}
