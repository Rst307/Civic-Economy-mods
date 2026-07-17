package org.civiceconomy.strength;

import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;

public final class MintComplianceSource {
    private final CivicDatabase database;
    private final MintComplianceCalculator calculator = new MintComplianceCalculator();

    public MintComplianceSource(CivicDatabase database) {
        if (database == null) {
            throw new IllegalArgumentException("Mint Compliance database is required");
        }
        this.database = database;
    }

    public MintComplianceAssessment assess(
            NationId nationId, long windowStartEpochMillis, long windowEndEpochMillis) {
        if (nationId == null) {
            throw new IllegalArgumentException("Mint Compliance Nation is required");
        }
        var observations = database.mintComplianceObservations(
                        nationId.value(), windowStartEpochMillis, windowEndEpochMillis)
                .stream()
                .map(stored -> new MintComplianceObservation(
                        stored.batchId(),
                        stored.openIncident()
                                ? MintComplianceOutcome.OPEN_INCIDENT
                                : !"COMMITTED".equals(stored.operationState())
                                        ? MintComplianceOutcome.QUARANTINED_RECOVERY
                                : stored.hadIncident()
                                        ? MintComplianceOutcome.RECOVERED_COMMIT
                                        : MintComplianceOutcome.CLEAN_COMMIT))
                .toList();
        return calculator.assess(
                nationId, windowStartEpochMillis, windowEndEpochMillis, observations);
    }
}
