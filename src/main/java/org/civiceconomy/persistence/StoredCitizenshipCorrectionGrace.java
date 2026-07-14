package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredCitizenshipCorrectionGrace(
        UUID graceId,
        UUID citizenshipId,
        UUID playerId,
        UUID nationId,
        UUID ftbTeamId,
        String startServiceIdentity,
        String startRequestId,
        String reason,
        long startedAtEpochMillis,
        long deadlineEpochMillis,
        String resolution,
        String resolutionServiceIdentity,
        String resolutionRequestId,
        String resolutionReason,
        Long resolvedAtEpochMillis) {}
