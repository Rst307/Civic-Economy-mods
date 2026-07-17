package org.civiceconomy.strength;

import org.civiceconomy.nation.NationId;

public record NationalStrengthRecalculation(
        NationId nationId,
        long recalculatedAtEpochMillis,
        NationalStrengthAssessment assessment,
        AuditableEconomicActivityWindowAssessment activityWindow) {
    public NationalStrengthRecalculation {
        if (nationId == null || recalculatedAtEpochMillis < 0L
                || assessment == null || activityWindow == null
                || !nationId.equals(activityWindow.nationId())) {
            throw new IllegalArgumentException("National Strength recalculation is invalid");
        }
    }
}
