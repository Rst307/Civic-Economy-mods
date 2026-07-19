package org.civiceconomy.territory;

import java.time.Instant;
import java.util.Optional;
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
        MoneyAmount restorationFee,
        TerritoryMaintenanceRestorationEligibility restorationEligibility,
        Optional<Instant> restorationCooldownEndsAt,
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
                || restorationFee == null
                || restorationEligibility == null
                || restorationCooldownEndsAt == null
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
        if ((restorationEligibility == TerritoryMaintenanceRestorationEligibility.NOT_REQUIRED)
                        != restorationCooldownEndsAt.isEmpty()
                || (restorationEligibility
                                == TerritoryMaintenanceRestorationEligibility.COOLDOWN_BLOCKED
                        && !restorationFee.equals(MoneyAmount.ZERO))) {
            throw new IllegalArgumentException(
                    "Territory Fiscal Assessment Restoration values are inconsistent");
        }
        maintenanceDue.plus(restorationFee);
    }

    public MoneyAmount totalDue() {
        return maintenanceDue.plus(restorationFee);
    }

    public boolean fundingEligible() {
        return restorationEligibility
                != TerritoryMaintenanceRestorationEligibility.COOLDOWN_BLOCKED;
    }
}
