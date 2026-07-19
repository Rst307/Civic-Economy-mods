package org.civiceconomy.strength;

import java.util.UUID;

public record MintComplianceObservation(UUID batchId, MintComplianceOutcome outcome) {
    public MintComplianceObservation {
        if (batchId == null || outcome == null) {
            throw new IllegalArgumentException("Mint Compliance Observation is invalid");
        }
    }
}
