package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredProductionInventoryExportHandoff(
        UUID handoffId,
        String serviceIdentity,
        String requestId,
        UUID exportId,
        UUID sourceInterfaceId,
        UUID destinationInterfaceId,
        UUID destinationReceiptId,
        UUID actorPlayerId,
        long recordedAtEpochMillis,
        String reason) {}
