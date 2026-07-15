package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryMaintenanceRestoration(
        UUID restorationId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID ftbTeamId,
        UUID actorPlayerId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        UUID sourceSuspendedAssessmentId,
        UUID policyId,
        long nextFullCycleStartsAtEpochMillis,
        long nextCyclePrepaymentMinorUnits,
        long restorationFeeMinorUnits,
        long totalDueMinorUnits,
        long remainingNextCycleCreditMinorUnits,
        long cooldownEndsAtEpochMillis,
        int destructionBasisPoints,
        String state,
        UUID reservationId,
        UUID publicFundPaymentId,
        UUID destructionOperationId,
        String reason,
        long preparedAtEpochMillis,
        Long committedAtEpochMillis) {}
