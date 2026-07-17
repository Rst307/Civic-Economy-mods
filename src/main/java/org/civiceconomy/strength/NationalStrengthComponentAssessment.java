package org.civiceconomy.strength;

public record NationalStrengthComponentAssessment(
        NationalStrengthComponent component,
        int normalizedInputBasisPoints,
        int weightPercent,
        int weightedContributionBasisPoints,
        NationalStrengthComponentState state) {
    public NationalStrengthComponentAssessment {
        if (component == null || state == null
                || normalizedInputBasisPoints < 0 || normalizedInputBasisPoints > 10_000
                || weightPercent < 0 || weightPercent > 100
                || weightedContributionBasisPoints < 0
                || weightedContributionBasisPoints > 10_000) {
            throw new IllegalArgumentException(
                    "National Strength component assessment is invalid");
        }
    }
}
