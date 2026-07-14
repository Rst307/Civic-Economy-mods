package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryMaintenanceSettlement(
        UUID settlementId,
        String serviceIdentity,
        String requestId,
        UUID cycleId,
        UUID nationId,
        UUID reservationId,
        UUID publicFundPaymentId,
        UUID destructionOperationId,
        String validity,
        String reason,
        long settledAtEpochMillis) {}
