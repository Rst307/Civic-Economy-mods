package org.civiceconomy.strength;

import java.util.HashSet;
import java.util.List;
import org.civiceconomy.nation.NationId;

public final class MintComplianceCalculator {
    public MintComplianceAssessment assess(
            NationId nationId,
            long windowStartEpochMillis,
            long windowEndEpochMillis,
            List<MintComplianceObservation> observations) {
        if (nationId == null || observations == null) {
            throw new IllegalArgumentException("Mint Compliance calculation is invalid");
        }
        var observedBatches = new HashSet<java.util.UUID>();
        long total = 0L;
        for (MintComplianceObservation observation : observations) {
            if (observation == null || !observedBatches.add(observation.batchId())) {
                throw new IllegalArgumentException(
                        "Mint Compliance observations must contain unique Batches");
            }
            total = Math.addExact(total, switch (observation.outcome()) {
                case CLEAN_COMMIT -> 10_000L;
                case RECOVERED_COMMIT -> 5_000L;
                case QUARANTINED_RECOVERY -> 0L;
                case OPEN_INCIDENT -> 0L;
            });
        }
        int normalized = observations.isEmpty()
                ? 0
                : Math.toIntExact(total / observations.size());
        boolean anomalous = observations.stream()
                .anyMatch(observation -> observation.outcome()
                                == MintComplianceOutcome.OPEN_INCIDENT
                        || observation.outcome()
                                == MintComplianceOutcome.QUARANTINED_RECOVERY);
        return new MintComplianceAssessment(
                nationId,
                windowStartEpochMillis,
                windowEndEpochMillis,
                observations,
                normalized,
                anomalous);
    }
}
