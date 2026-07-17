package org.civiceconomy.strength;

import org.civiceconomy.nation.NationEffectiveCitizenPopulation;
import org.civiceconomy.nation.NationId;

public record NationalStrengthRecalculation(
        NationId nationId,
        long recalculatedAtEpochMillis,
        NationalStrengthAssessment assessment,
        NationEffectiveCitizenPopulation effectiveCitizenPopulation,
        EffectiveTerritoryStrengthAssessment effectiveTerritory,
        MintComplianceAssessment mintCompliance,
        AuditableEconomicActivityWindowAssessment activityWindow) {
    public NationalStrengthRecalculation {
        if (nationId == null || recalculatedAtEpochMillis < 0L
                || assessment == null || effectiveCitizenPopulation == null
                || effectiveTerritory == null || mintCompliance == null
                || activityWindow == null
                || !nationId.equals(effectiveCitizenPopulation.nationId())
                || effectiveCitizenPopulation.asOf().toEpochMilli()
                        != recalculatedAtEpochMillis
                || !nationId.equals(effectiveTerritory.nationId())
                || !nationId.equals(mintCompliance.nationId())
                || !nationId.equals(activityWindow.nationId())) {
            throw new IllegalArgumentException("National Strength recalculation is invalid");
        }
    }
}
