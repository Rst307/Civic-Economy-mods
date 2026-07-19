package org.civiceconomy.territory;

import java.time.Duration;
import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleTerritoryMaintenancePolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        Duration cycleDuration,
        long baseMaintenancePerChargeableClaimMinorUnits,
        int enclaveAndCrossDimensionMultiplierBasisPoints,
        long forceLoadSurchargeMinorUnits,
        Duration forceLoadGrace,
        long restorationFeeMinorUnits,
        Duration restorationCooldown,
        int destructionBasisPoints,
        Instant effectiveAt,
        String reason) {
    public ScheduleTerritoryMaintenancePolicy {
        if (serviceIdentity == null
                || cycleDuration == null
                || forceLoadGrace == null
                || restorationCooldown == null
                || effectiveAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance policy request cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || actorIdentity == null
                || actorIdentity.isBlank()
                || cycleDuration.isZero()
                || cycleDuration.isNegative()
                || baseMaintenancePerChargeableClaimMinorUnits < 0L
                || enclaveAndCrossDimensionMultiplierBasisPoints < 10_000
                || forceLoadSurchargeMinorUnits < 0L
                || forceLoadGrace.isZero()
                || forceLoadGrace.isNegative()
                || restorationFeeMinorUnits < 0L
                || restorationCooldown.isZero()
                || restorationCooldown.isNegative()
                || destructionBasisPoints < 3_000
                || destructionBasisPoints > 8_000
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException("Territory Maintenance policy values are invalid");
        }
        if (cycleDuration.toMillis() <= 0L) {
            throw new IllegalArgumentException(
                    "Territory Maintenance cycle duration must be at least one millisecond");
        }
        if (restorationCooldown.toMillis() <= 0L) {
            throw new IllegalArgumentException(
                    "Territory Maintenance restoration cooldown must be at least one millisecond");
        }
        if (forceLoadGrace.toMillis() <= 0L) {
            throw new IllegalArgumentException(
                    "Territory Force-load grace must be at least one millisecond");
        }
    }
}
