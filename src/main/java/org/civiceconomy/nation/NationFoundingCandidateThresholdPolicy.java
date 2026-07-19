package org.civiceconomy.nation;

public record NationFoundingCandidateThresholdPolicy(int minimumEffectiveCandidates) {
    public NationFoundingCandidateThresholdPolicy {
        if (minimumEffectiveCandidates < 1) {
            throw new IllegalArgumentException("Formal founding candidate threshold must be positive");
        }
    }
}
