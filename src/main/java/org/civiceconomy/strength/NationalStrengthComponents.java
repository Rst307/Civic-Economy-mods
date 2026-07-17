package org.civiceconomy.strength;

import java.util.Set;

public record NationalStrengthComponents(
        int effectiveCitizensBasisPoints,
        int productionAndInfrastructureBasisPoints,
        int auditableEconomicActivityBasisPoints,
        int effectiveTerritoryBasisPoints,
        int complianceBasisPoints,
        Set<NationalStrengthComponent> anomalousComponents) {
    public NationalStrengthComponents {
        requireBasisPoints(effectiveCitizensBasisPoints);
        requireBasisPoints(productionAndInfrastructureBasisPoints);
        requireBasisPoints(auditableEconomicActivityBasisPoints);
        requireBasisPoints(effectiveTerritoryBasisPoints);
        requireBasisPoints(complianceBasisPoints);
        if (anomalousComponents == null || anomalousComponents.contains(null)) {
            throw new IllegalArgumentException("National Strength anomalies cannot contain null");
        }
        anomalousComponents = Set.copyOf(anomalousComponents);
    }

    private static void requireBasisPoints(int value) {
        if (value < 0 || value > 10_000) {
            throw new IllegalArgumentException(
                    "National Strength component must be between 0 and 10000 basis points");
        }
    }
}
