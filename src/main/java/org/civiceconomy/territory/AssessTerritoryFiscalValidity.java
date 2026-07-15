package org.civiceconomy.territory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record AssessTerritoryFiscalValidity(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID cycleId,
        NationId nationId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long maintenanceDueMinorUnits,
        long restorationFeeMinorUnits,
        TerritoryMaintenanceRestorationEligibility restorationEligibility,
        Optional<Instant> restorationCooldownEndsAt,
        TerritoryMaintenancePriority priority,
        String reason) {
    public AssessTerritoryFiscalValidity {
        if (serviceIdentity == null
                || cycleId == null
                || nationId == null
                || ftbTeamId == null
                || restorationEligibility == null
                || restorationCooldownEndsAt == null
                || priority == null) {
            throw new IllegalArgumentException(
                    "Territory Fiscal Assessment cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || dimensionId == null
                || dimensionId.isBlank()
                || reason == null
                || reason.isBlank()
                || maintenanceDueMinorUnits < 0L
                || restorationFeeMinorUnits < 0L) {
            throw new IllegalArgumentException("Territory Fiscal Assessment values are invalid");
        }
        if ((restorationEligibility == TerritoryMaintenanceRestorationEligibility.NOT_REQUIRED)
                        != restorationCooldownEndsAt.isEmpty()
                || (restorationEligibility
                                == TerritoryMaintenanceRestorationEligibility.COOLDOWN_BLOCKED
                        && restorationFeeMinorUnits != 0L)) {
            throw new IllegalArgumentException(
                    "Territory Fiscal Assessment Restoration values are inconsistent");
        }
        Math.addExact(maintenanceDueMinorUnits, restorationFeeMinorUnits);
    }

    public AssessTerritoryFiscalValidity(
            ServiceIdentity serviceIdentity,
            String requestId,
            UUID cycleId,
            NationId nationId,
            UUID ftbTeamId,
            String dimensionId,
            int chunkX,
            int chunkZ,
            long maintenanceDueMinorUnits,
            TerritoryMaintenancePriority priority,
            String reason) {
        this(
                serviceIdentity,
                requestId,
                cycleId,
                nationId,
                ftbTeamId,
                dimensionId,
                chunkX,
                chunkZ,
                maintenanceDueMinorUnits,
                0L,
                TerritoryMaintenanceRestorationEligibility.NOT_REQUIRED,
                Optional.empty(),
                priority,
                reason);
    }

    public AssessTerritoryFiscalValidity(
            ServiceIdentity serviceIdentity,
            String requestId,
            UUID cycleId,
            NationId nationId,
            UUID ftbTeamId,
            String dimensionId,
            int chunkX,
            int chunkZ,
            long maintenanceDueMinorUnits,
            String reason) {
        this(
                serviceIdentity,
                requestId,
                cycleId,
                nationId,
                ftbTeamId,
                dimensionId,
                chunkX,
                chunkZ,
                maintenanceDueMinorUnits,
                0L,
                TerritoryMaintenanceRestorationEligibility.NOT_REQUIRED,
                Optional.empty(),
                TerritoryMaintenancePriority.ORDINARY,
                reason);
    }
}
