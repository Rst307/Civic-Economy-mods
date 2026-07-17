package org.civiceconomy.production;

import java.util.List;

public record MachineInventoryDelta(
        List<MachineInventoryChange> inputs,
        List<MachineInventoryChange> outputs) {
    public MachineInventoryDelta {
        if (inputs == null || inputs.isEmpty() || outputs == null || outputs.isEmpty()
                || inputs.stream().anyMatch(java.util.Objects::isNull)
                || outputs.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Machine inventory delta is invalid");
        }
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }
}
