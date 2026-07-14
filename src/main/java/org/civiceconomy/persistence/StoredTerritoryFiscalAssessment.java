package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryFiscalAssessment(
        UUID assessmentId,
        String serviceIdentity,
        String requestId,
        UUID cycleId,
        UUID nationId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long maintenanceDueMinorUnits,
        String priority,
        String validity,
        String reason,
        long assessedAtEpochMillis) {}
