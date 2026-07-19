package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryForceLoadEnforcement(
        UUID enforcementId,
        String serviceIdentity,
        String requestId,
        UUID assessmentId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        String state,
        String reason,
        long notBeforeEpochMillis,
        long preparedAtEpochMillis,
        Long externalAppliedAtEpochMillis,
        Long committedAtEpochMillis) {}
