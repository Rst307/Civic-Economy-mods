package org.civiceconomy.strength;

import org.civiceconomy.nation.NationEffectiveCitizenPopulation;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.production.RollingProductionMarginalReturnAssessment;
import java.util.List;

public record NationalStrengthRecalculation(
        NationId nationId,
        long recalculatedAtEpochMillis,
        NationalStrengthAssessment assessment,
        NationEffectiveCitizenPopulation effectiveCitizenPopulation,
        EffectiveTerritoryStrengthAssessment effectiveTerritory,
        MintComplianceAssessment mintCompliance,
        RollingProductionMarginalReturnAssessment productionMarginalReturn,
        AuditableEconomicActivityWindowAssessment activityWindow) {
    public NationalStrengthRecalculation(
            NationId nationId,
            long recalculatedAtEpochMillis,
            NationalStrengthAssessment assessment,
            NationEffectiveCitizenPopulation effectiveCitizenPopulation,
            EffectiveTerritoryStrengthAssessment effectiveTerritory,
            MintComplianceAssessment mintCompliance,
            AuditableEconomicActivityWindowAssessment activityWindow) {
        this(
                nationId,
                recalculatedAtEpochMillis,
                assessment,
                effectiveCitizenPopulation,
                effectiveTerritory,
                mintCompliance,
                emptyProduction(nationId, recalculatedAtEpochMillis),
                activityWindow);
    }

    public NationalStrengthRecalculation {
        if (nationId == null || recalculatedAtEpochMillis < 0L
                || assessment == null || effectiveCitizenPopulation == null
                || effectiveTerritory == null || mintCompliance == null
                || productionMarginalReturn == null
                || activityWindow == null
                || !nationId.equals(effectiveCitizenPopulation.nationId())
                || effectiveCitizenPopulation.asOf().toEpochMilli()
                        != recalculatedAtEpochMillis
                || !nationId.equals(effectiveTerritory.nationId())
                || !nationId.equals(mintCompliance.nationId())
                || !nationId.equals(productionMarginalReturn.nationId())
                || productionMarginalReturn.windowEndEpochMillis()
                        != recalculatedAtEpochMillis
                || !nationId.equals(activityWindow.nationId())) {
            throw new IllegalArgumentException("National Strength recalculation is invalid");
        }
    }

    private static RollingProductionMarginalReturnAssessment emptyProduction(
            NationId nationId, long recalculatedAtEpochMillis) {
        return new RollingProductionMarginalReturnAssessment(
                nationId,
                Math.max(0L, recalculatedAtEpochMillis - 1L),
                recalculatedAtEpochMillis,
                0L, 0L, 0L, 0L, 0L, 0L, 0, 0, List.of());
    }
}
