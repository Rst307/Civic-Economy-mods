package org.civiceconomy.strength;

import java.time.Duration;

public record MintCompliancePolicy(
        Duration observationWindow,
        int recoveredCommitBasisPoints) {
    public MintCompliancePolicy {
        if (observationWindow == null || observationWindow.isZero() || observationWindow.isNegative()) {
            throw new IllegalArgumentException("Mint Compliance observation window must be positive");
        }
        if (recoveredCommitBasisPoints < 0 || recoveredCommitBasisPoints > 10_000) {
            throw new IllegalArgumentException(
                    "Mint Compliance recovered commit weight must be between 0 and 10000 basis points");
        }
    }
}
