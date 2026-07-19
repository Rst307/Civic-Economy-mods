package org.civiceconomy.strength;

import java.util.Map;
import org.civiceconomy.nation.NationId;

public record AuditableEconomicActivityWindowAssessment(
        NationId nationId,
        long windowStartEpochMillis,
        long windowEndEpochMillis,
        long acceptedValueMinorUnits,
        int normalizedBasisPoints,
        int acceptedCount,
        int excludedCount,
        Map<AuditableEconomicActivityDecision, Integer> excludedByDecision) {
    public AuditableEconomicActivityWindowAssessment {
        if (nationId == null || windowStartEpochMillis < 0L
                || windowEndEpochMillis <= windowStartEpochMillis
                || acceptedValueMinorUnits < 0L || normalizedBasisPoints < 0
                || normalizedBasisPoints > 10_000 || acceptedCount < 0 || excludedCount < 0
                || excludedByDecision == null
                || excludedByDecision.keySet().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Auditable Economic Activity window assessment is invalid");
        }
        excludedByDecision = Map.copyOf(excludedByDecision);
    }
}
