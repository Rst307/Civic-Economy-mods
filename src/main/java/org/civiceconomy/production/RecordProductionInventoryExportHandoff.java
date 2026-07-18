package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

/** Server-authoritative request to bind one EXPORT to its explicit destination Receipt. */
public record RecordProductionInventoryExportHandoff(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID exportId,
        UUID destinationInterfaceId,
        UUID destinationReceiptId,
        UUID actorPlayerId,
        String reason) {
    public RecordProductionInventoryExportHandoff {
        if (serviceIdentity == null || requestId == null || requestId.isBlank()
                || exportId == null || destinationInterfaceId == null
                || destinationReceiptId == null || actorPlayerId == null
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Production export handoff request is invalid");
        }
    }
}
