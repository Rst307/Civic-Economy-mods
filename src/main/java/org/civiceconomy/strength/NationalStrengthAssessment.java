package org.civiceconomy.strength;

import java.util.Map;

public record NationalStrengthAssessment(
        int effectiveCitizenContribution,
        int productionAndInfrastructureContribution,
        int auditableEconomicActivityContribution,
        int effectiveTerritoryContribution,
        int complianceContribution,
        int totalBasisPoints,
        Map<NationalStrengthComponent, NationalStrengthComponentAssessment> components,
        boolean newMintAllocationPaused) {
    public NationalStrengthAssessment {
        components = Map.copyOf(components);
    }

    public NationalStrengthComponentState componentState(
            NationalStrengthComponent component) {
        return component(component).state();
    }

    public NationalStrengthComponentAssessment component(
            NationalStrengthComponent component) {
        NationalStrengthComponentAssessment assessment = components.get(component);
        if (assessment == null) {
            throw new IllegalArgumentException("Unknown National Strength component " + component);
        }
        return assessment;
    }
}
