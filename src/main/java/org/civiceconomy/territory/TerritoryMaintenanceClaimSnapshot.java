package org.civiceconomy.territory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record TerritoryMaintenanceClaimSnapshot(
        NationId nationId,
        UUID ftbTeamId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long maintenanceDueMinorUnits,
        long restorationFeeMinorUnits,
        TerritoryMaintenanceRestorationEligibility restorationEligibility,
        Optional<Instant> restorationCooldownEndsAt,
        TerritoryMaintenancePriority priority) {
    public TerritoryMaintenanceClaimSnapshot {
        if (nationId == null
                || ftbTeamId == null
                || restorationEligibility == null
                || restorationCooldownEndsAt == null
                || priority == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Claim Snapshot cannot contain null values");
        }
        if (dimensionId == null
                || dimensionId.isBlank()
                || maintenanceDueMinorUnits < 0L
                || restorationFeeMinorUnits < 0L) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Claim Snapshot values are invalid");
        }
        if ((restorationEligibility == TerritoryMaintenanceRestorationEligibility.NOT_REQUIRED)
                        != restorationCooldownEndsAt.isEmpty()
                || (restorationEligibility
                                == TerritoryMaintenanceRestorationEligibility.COOLDOWN_BLOCKED
                        && restorationFeeMinorUnits != 0L)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration Claim Snapshot is inconsistent");
        }
        Math.addExact(maintenanceDueMinorUnits, restorationFeeMinorUnits);
    }

    public TerritoryMaintenanceClaimSnapshot(
            NationId nationId,
            UUID ftbTeamId,
            String dimensionId,
            int chunkX,
            int chunkZ,
            long maintenanceDueMinorUnits,
            TerritoryMaintenancePriority priority) {
        this(
                nationId,
                ftbTeamId,
                dimensionId,
                chunkX,
                chunkZ,
                maintenanceDueMinorUnits,
                0L,
                TerritoryMaintenanceRestorationEligibility.NOT_REQUIRED,
                Optional.empty(),
                priority);
    }

    public long totalDueMinorUnits() {
        return Math.addExact(maintenanceDueMinorUnits, restorationFeeMinorUnits);
    }

    public boolean fundingEligible() {
        return restorationEligibility
                != TerritoryMaintenanceRestorationEligibility.COOLDOWN_BLOCKED;
    }
}
