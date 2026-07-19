package org.civiceconomy.platform.neoforge;

import java.util.List;
import org.civiceconomy.production.FacilityAccountingInterfacePosition;
import org.civiceconomy.production.MachineInventoryChange;

record FacilityAccountingInterfaceInventoryIncrease(
        FacilityAccountingInterfacePosition position,
        long observedAtEpochMillis,
        List<MachineInventoryChange> receivedOutputs) {
    FacilityAccountingInterfaceInventoryIncrease {
        if (position == null || observedAtEpochMillis < 0L || receivedOutputs == null
                || receivedOutputs.isEmpty()
                || receivedOutputs.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface inventory increase is invalid");
        }
        receivedOutputs = List.copyOf(receivedOutputs);
    }
}
