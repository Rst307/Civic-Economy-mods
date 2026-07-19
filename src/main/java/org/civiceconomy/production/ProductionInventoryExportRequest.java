package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ProductionInventoryExportRequest(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID interfaceId,
        UUID actorPlayerId,
        int slot,
        int count,
        ProductionInventoryExportKind kind,
        String destinationReference,
        long exportedAtEpochMillis,
        String reason) {
    public ProductionInventoryExportRequest {
        if (serviceIdentity == null || requestId == null || requestId.isBlank()
                || interfaceId == null || actorPlayerId == null || slot < 0 || count <= 0
                || kind == null || destinationReference == null
                || destinationReference.isBlank() || exportedAtEpochMillis < 0L
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Production inventory export request is invalid");
        }
    }
}
