package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ProductionInventoryConsumption(
        UUID consumptionId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID interfaceId,
        String itemId,
        String componentFingerprint,
        int consumedCount,
        long consumedAtEpochMillis,
        String reason) {
    public ProductionInventoryConsumption {
        if (consumptionId == null || serviceIdentity == null || requestId == null
                || requestId.isBlank() || interfaceId == null || itemId == null
                || itemId.isBlank() || componentFingerprint == null
                || componentFingerprint.isBlank() || consumedCount <= 0
                || consumedAtEpochMillis < 0L || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Production inventory consumption is invalid");
        }
    }
}
