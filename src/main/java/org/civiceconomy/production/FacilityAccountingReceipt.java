package org.civiceconomy.production;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record FacilityAccountingReceipt(
        UUID receiptId,
        UUID interfaceId,
        FacilityAccountingInterfacePosition position,
        long observedAtEpochMillis,
        List<MachineInventoryChange> receivedOutputs) {
    public FacilityAccountingReceipt {
        if (receiptId == null || interfaceId == null || position == null
                || observedAtEpochMillis < 0L || receivedOutputs == null
                || receivedOutputs.isEmpty()
                || receivedOutputs.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Facility Accounting Receipt is invalid");
        }
        if (new HashSet<>(receivedOutputs.stream()
                        .map(MachineInventoryChange::slot)
                        .toList())
                .size() != receivedOutputs.size()) {
            throw new IllegalArgumentException(
                    "Facility Accounting Receipt contains a duplicate slot");
        }
        receivedOutputs = List.copyOf(receivedOutputs);
    }
}
