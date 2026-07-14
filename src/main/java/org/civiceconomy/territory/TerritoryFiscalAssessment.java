package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record TerritoryFiscalAssessment(
        UUID assessmentId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID cycleId,
        NationId nationId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        MoneyAmount maintenanceDue,
        TerritoryMaintenancePriority priority,
        TerritoryFiscalValidity validity,
        String reason,
        Instant assessedAt) {
    public TerritoryFiscalAssessment {
        if (assessmentId == null
                || serviceIdentity == null
                || cycleId == null
                || nationId == null
                || ftbTeamId == null
                || maintenanceDue == null
                || priority == null
                || validity == null
                || assessedAt == null) {
            throw new IllegalArgumentException(
                    "Territory Fiscal Assessment cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || dimensionId == null
                || dimensionId.isBlank()
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException("Territory Fiscal Assessment values are invalid");
        }
    }
}
