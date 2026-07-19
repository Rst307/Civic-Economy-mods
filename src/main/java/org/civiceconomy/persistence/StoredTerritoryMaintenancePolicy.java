package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryMaintenancePolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long cycleDurationMillis,
        long baseMaintenancePerChargeableClaimMinorUnits,
        int enclaveAndCrossDimensionMultiplierBasisPoints,
        long forceLoadSurchargeMinorUnits,
        Long forceLoadGraceMillis,
        long restorationFeeMinorUnits,
        long restorationCooldownMillis,
        int destructionBasisPoints,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
