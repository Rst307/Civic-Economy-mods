package org.civiceconomy.strength;

import java.util.EnumMap;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredAuditableEconomicActivityEvidence;

public final class AuditableEconomicActivityWindow {
    private final CivicDatabase database;
    private final DiminishingStrengthNormalizer normalizer;

    public AuditableEconomicActivityWindow(CivicDatabase database, long fullStrengthScale) {
        if (database == null) {
            throw new IllegalArgumentException("Auditable Economic Activity database is required");
        }
        this.database = database;
        this.normalizer = new DiminishingStrengthNormalizer(fullStrengthScale);
    }

    public AuditableEconomicActivityWindowAssessment assess(
            NationId nationId, long windowStartEpochMillis, long windowEndEpochMillis) {
        if (nationId == null) {
            throw new IllegalArgumentException("Auditable Economic Activity Nation is required");
        }
        long acceptedValue = 0L;
        int acceptedCount = 0;
        int excludedCount = 0;
        EnumMap<AuditableEconomicActivityDecision, Integer> excludedByDecision =
                new EnumMap<>(AuditableEconomicActivityDecision.class);
        for (StoredAuditableEconomicActivityEvidence evidence : database
                .auditableEconomicActivityEvidence(
                        nationId.value(), windowStartEpochMillis, windowEndEpochMillis)) {
            AuditableEconomicActivityDecision decision = currentDecision(evidence);
            if (decision == AuditableEconomicActivityDecision.INCLUDED) {
                acceptedValue = Math.addExact(acceptedValue, evidence.includedValueMinorUnits());
                acceptedCount++;
            } else {
                excludedCount++;
                excludedByDecision.merge(decision, 1, Integer::sum);
            }
        }
        return new AuditableEconomicActivityWindowAssessment(
                nationId,
                windowStartEpochMillis,
                windowEndEpochMillis,
                acceptedValue,
                normalizer.normalize(acceptedValue),
                acceptedCount,
                excludedCount,
                excludedByDecision);
    }

    private AuditableEconomicActivityDecision currentDecision(
            StoredAuditableEconomicActivityEvidence evidence) {
        var payment = database.paymentTransaction(evidence.paymentTransactionId());
        if (payment != null && "COMPENSATED".equals(payment.state())) {
            return AuditableEconomicActivityDecision.EXCLUDED_REFUNDED;
        }
        if (payment == null || !"CIVIC_COMMITTED".equals(payment.state())) {
            return AuditableEconomicActivityDecision.EXCLUDED_NOT_COMMITTED;
        }
        if (payment.refundedMinorUnits() != 0L) {
            return AuditableEconomicActivityDecision.EXCLUDED_REFUNDED;
        }
        return AuditableEconomicActivityDecision.valueOf(evidence.decision());
    }
}
