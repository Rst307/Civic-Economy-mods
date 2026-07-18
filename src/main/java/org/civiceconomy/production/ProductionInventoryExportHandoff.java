package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

/** Immutable, explicit relationship between a source EXPORT and a destination Receipt. */
public record ProductionInventoryExportHandoff(
        UUID handoffId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID exportId,
        UUID sourceInterfaceId,
        UUID destinationInterfaceId,
        UUID destinationReceiptId,
        UUID actorPlayerId,
        long recordedAtEpochMillis,
        String reason) {
    public ProductionInventoryExportHandoff {
        if (handoffId == null || serviceIdentity == null || requestId == null || requestId.isBlank()
                || exportId == null || sourceInterfaceId == null || destinationInterfaceId == null
                || sourceInterfaceId.equals(destinationInterfaceId) || destinationReceiptId == null
                || actorPlayerId == null || recordedAtEpochMillis < 0L
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Production export handoff is invalid");
        }
    }
}
