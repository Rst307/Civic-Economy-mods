package org.civiceconomy.strength;

import java.util.EnumMap;

public final class NationalStrengthCalculator {
    public NationalStrengthAssessment assess(NationalStrengthComponents components) {
        if (components == null) {
            throw new IllegalArgumentException("National Strength components cannot be null");
        }
        NationalStrengthComponentAssessment citizens = component(
                components,
                NationalStrengthComponent.EFFECTIVE_CITIZENS,
                components.effectiveCitizensBasisPoints(),
                35);
        NationalStrengthComponentAssessment production = component(
                components,
                NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE,
                components.productionAndInfrastructureBasisPoints(),
                25);
        NationalStrengthComponentAssessment economy = component(
                components,
                NationalStrengthComponent.AUDITABLE_ECONOMIC_ACTIVITY,
                components.auditableEconomicActivityBasisPoints(),
                20);
        NationalStrengthComponentAssessment territory = component(
                components,
                NationalStrengthComponent.EFFECTIVE_TERRITORY,
                components.effectiveTerritoryBasisPoints(),
                10);
        NationalStrengthComponentAssessment compliance = component(
                components,
                NationalStrengthComponent.COMPLIANCE,
                components.complianceBasisPoints(),
                10);
        EnumMap<NationalStrengthComponent, NationalStrengthComponentAssessment> details =
                new EnumMap<>(NationalStrengthComponent.class);
        details.put(citizens.component(), citizens);
        details.put(production.component(), production);
        details.put(economy.component(), economy);
        details.put(territory.component(), territory);
        details.put(compliance.component(), compliance);
        return new NationalStrengthAssessment(
                citizens.weightedContributionBasisPoints(),
                production.weightedContributionBasisPoints(),
                economy.weightedContributionBasisPoints(),
                territory.weightedContributionBasisPoints(),
                compliance.weightedContributionBasisPoints(),
                citizens.weightedContributionBasisPoints()
                        + production.weightedContributionBasisPoints()
                        + economy.weightedContributionBasisPoints()
                        + territory.weightedContributionBasisPoints()
                        + compliance.weightedContributionBasisPoints(),
                details,
                !components.anomalousComponents().isEmpty());
    }

    private static NationalStrengthComponentAssessment component(
            NationalStrengthComponents components,
            NationalStrengthComponent component,
            int normalizedBasisPoints,
            int weightPercent) {
        boolean anomalous = components.anomalousComponents().contains(component);
        return new NationalStrengthComponentAssessment(
                component,
                normalizedBasisPoints,
                weightPercent,
                anomalous ? 0 : Math.multiplyExact(normalizedBasisPoints, weightPercent) / 100,
                anomalous
                        ? NationalStrengthComponentState.PAUSED_ANOMALY
                        : NationalStrengthComponentState.ACTIVE);
    }
}
