package org.civiceconomy.strength;

import java.util.List;
import org.civiceconomy.nation.NationId;

public record MintComplianceAssessment(
        NationId nationId,
        long windowStartEpochMillis,
        long windowEndEpochMillis,
        List<MintComplianceObservation> observations,
        int normalizedBasisPoints,
        boolean anomalous) {
    public MintComplianceAssessment {
        if (nationId == null || windowStartEpochMillis < 0L
                || windowEndEpochMillis <= windowStartEpochMillis
                || observations == null
                || observations.stream().anyMatch(java.util.Objects::isNull)
                || normalizedBasisPoints < 0 || normalizedBasisPoints > 10_000) {
            throw new IllegalArgumentException("Mint Compliance assessment is invalid");
        }
        observations = List.copyOf(observations);
    }

    public int observationCount() {
        return observations.size();
    }

    public int cleanCommitCount() {
        return count(MintComplianceOutcome.CLEAN_COMMIT);
    }

    public int recoveredCommitCount() {
        return count(MintComplianceOutcome.RECOVERED_COMMIT);
    }

    public int quarantinedRecoveryCount() {
        return count(MintComplianceOutcome.QUARANTINED_RECOVERY);
    }

    public int openIncidentCount() {
        return count(MintComplianceOutcome.OPEN_INCIDENT);
    }

    private int count(MintComplianceOutcome outcome) {
        return (int) observations.stream()
                .filter(observation -> observation.outcome() == outcome)
                .count();
    }
}
