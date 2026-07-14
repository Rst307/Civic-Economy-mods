package org.civiceconomy.persistence;

import java.util.UUID;
import java.util.List;

public record StoredTerritoryMaintenanceSettlement(
        UUID settlementId,
        String serviceIdentity,
        String requestId,
        UUID cycleId,
        UUID nationId,
        UUID reservationId,
        UUID publicFundPaymentId,
        UUID destructionOperationId,
        String outcome,
        List<UUID> fundedAssessmentIds,
        List<UUID> suspendedAssessmentIds,
        String reason,
        long settledAtEpochMillis) {
    public StoredTerritoryMaintenanceSettlement {
        fundedAssessmentIds = List.copyOf(fundedAssessmentIds);
        suspendedAssessmentIds = List.copyOf(suspendedAssessmentIds);
    }
}
