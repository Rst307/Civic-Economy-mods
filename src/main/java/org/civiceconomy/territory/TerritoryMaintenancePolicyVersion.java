package org.civiceconomy.territory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record TerritoryMaintenancePolicyVersion(
        UUID policyId,
        Duration cycleDuration,
        long baseMaintenancePerChargeableClaimMinorUnits,
        int enclaveAndCrossDimensionMultiplierBasisPoints,
        long forceLoadSurchargeMinorUnits,
        long restorationFeeMinorUnits,
        Duration restorationCooldown,
        int destructionBasisPoints,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public TerritoryMaintenancePolicyVersion {
        if (policyId == null
                || cycleDuration == null
                || restorationCooldown == null
                || effectiveAt == null
                || actorIdentity == null
                || reason == null
                || recordedAt == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance policy cannot contain null values");
        }
        if (cycleDuration.isZero()
                || cycleDuration.isNegative()
                || baseMaintenancePerChargeableClaimMinorUnits < 0L
                || enclaveAndCrossDimensionMultiplierBasisPoints < 10_000
                || forceLoadSurchargeMinorUnits < 0L
                || restorationFeeMinorUnits < 0L
                || restorationCooldown.isZero()
                || restorationCooldown.isNegative()
                || destructionBasisPoints < 3_000
                || destructionBasisPoints > 8_000
                || actorIdentity.isBlank()
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
    }
}
