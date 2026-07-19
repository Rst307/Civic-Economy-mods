package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ProductionInventoryConsumptionRequest(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID interfaceId,
        String itemId,
        String componentFingerprint,
        int count,
        long consumedAtEpochMillis,
        String reason) {
    public ProductionInventoryConsumptionRequest {
        if (serviceIdentity == null || requestId == null || requestId.isBlank()
                || interfaceId == null || itemId == null || itemId.isBlank()
                || componentFingerprint == null || componentFingerprint.isBlank()
                || count <= 0 || consumedAtEpochMillis < 0L
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Production inventory consumption request is invalid");
        }
    }
}
