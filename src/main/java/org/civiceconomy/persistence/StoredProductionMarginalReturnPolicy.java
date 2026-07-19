package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredProductionMarginalReturnPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long facilitySoftCapMinorUnits,
        int facilityExcessWeightBasisPoints,
        long industrySoftCapMinorUnits,
        int industryExcessWeightBasisPoints,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
