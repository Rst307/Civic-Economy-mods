package org.civiceconomy.production;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record FacilityAccountingBaseline(
        UUID baselineId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID facilityId,
        UUID interfaceId,
        String createVersion,
        List<FacilityBaselineMachine> machines,
        List<MachineInventoryChange> startingInventory,
        UUID actorPlayerId,
        FacilityAccountingBaselineState state,
        String reason,
        Instant capturedAt,
        Instant activatedAt) {
    public FacilityAccountingBaseline {
        if (baselineId == null || serviceIdentity == null
                || requestId == null || requestId.isBlank()
                || facilityId == null || interfaceId == null
                || createVersion == null || createVersion.isBlank()
                || machines == null || machines.isEmpty()
                || machines.stream().anyMatch(java.util.Objects::isNull)
                || startingInventory == null
                || startingInventory.stream().anyMatch(java.util.Objects::isNull)
                || actorPlayerId == null || state == null
                || reason == null || reason.isBlank() || capturedAt == null
                || (state == FacilityAccountingBaselineState.CAPTURED && activatedAt != null)
                || (state == FacilityAccountingBaselineState.ACTIVE && activatedAt == null)) {
            throw new IllegalArgumentException("Facility Accounting Baseline is invalid");
        }
        machines = List.copyOf(machines);
        startingInventory = List.copyOf(startingInventory);
    }
}
