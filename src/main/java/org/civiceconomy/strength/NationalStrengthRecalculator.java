package org.civiceconomy.strength;

import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationEffectiveCitizenPopulation;
import org.civiceconomy.persistence.CivicDatabase;

public final class NationalStrengthRecalculator {
    private final AuditableEconomicActivityWindow activityWindow;
    private final long activityWindowMillis;
    private final NationalStrengthCalculator calculator = new NationalStrengthCalculator();

    public NationalStrengthRecalculator(
            CivicDatabase database, long activityWindowMillis, long activityFullStrengthScale) {
        if (database == null || activityWindowMillis <= 0L) {
            throw new IllegalArgumentException(
                    "National Strength recalculation dependencies are invalid");
        }
        this.activityWindow =
                new AuditableEconomicActivityWindow(database, activityFullStrengthScale);
        this.activityWindowMillis = activityWindowMillis;
    }

    public NationalStrengthRecalculation recalculate(
            NationId nationId,
            long recalculatedAtEpochMillis,
            NationEffectiveCitizenPopulation effectiveCitizenPopulation,
            EffectiveTerritoryStrengthAssessment effectiveTerritory,
            MintComplianceAssessment mintCompliance,
            NationalStrengthComponents serverDerivedComponents) {
        if (nationId == null || recalculatedAtEpochMillis <= 0L
                || effectiveCitizenPopulation == null || effectiveTerritory == null
                || mintCompliance == null
                || serverDerivedComponents == null
                || !nationId.equals(effectiveCitizenPopulation.nationId())
                || effectiveCitizenPopulation.asOf().toEpochMilli()
                        != recalculatedAtEpochMillis
                || !nationId.equals(effectiveTerritory.nationId())
                || !nationId.equals(mintCompliance.nationId())) {
            throw new IllegalArgumentException("National Strength recalculation request is invalid");
        }
        long windowStart = recalculatedAtEpochMillis <= activityWindowMillis
                ? 0L
                : recalculatedAtEpochMillis - activityWindowMillis;
        AuditableEconomicActivityWindowAssessment activity = activityWindow.assess(
                nationId, windowStart, recalculatedAtEpochMillis);
        NationalStrengthComponents authoritative = new NationalStrengthComponents(
                serverDerivedComponents.effectiveCitizensBasisPoints(),
                serverDerivedComponents.productionAndInfrastructureBasisPoints(),
                activity.normalizedBasisPoints(),
                serverDerivedComponents.effectiveTerritoryBasisPoints(),
                serverDerivedComponents.complianceBasisPoints(),
                serverDerivedComponents.anomalousComponents());
        return new NationalStrengthRecalculation(
                nationId,
                recalculatedAtEpochMillis,
                calculator.assess(authoritative),
                effectiveCitizenPopulation,
                effectiveTerritory,
                mintCompliance,
                activity);
    }
}
