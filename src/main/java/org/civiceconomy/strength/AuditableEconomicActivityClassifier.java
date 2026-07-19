package org.civiceconomy.strength;

import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentKind;
import org.civiceconomy.fiscal.TransactionState;

public final class AuditableEconomicActivityClassifier {
    public AuditableEconomicActivityAssessment assess(
            AuditableEconomicActivityEvidence evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity Evidence cannot be null");
        }
        var transaction = evidence.transaction();
        if (transaction.refundedAmount().minorUnits() != 0L) {
            return assessment(
                    evidence,
                    AuditableEconomicActivityDecision.EXCLUDED_REFUNDED,
                    MoneyAmount.ZERO);
        }
        if (evidence.payerController().equals(evidence.recipientController())) {
            return assessment(
                    evidence,
                    AuditableEconomicActivityDecision.EXCLUDED_COMMON_CONTROL,
                    MoneyAmount.ZERO);
        }
        if (transaction.state() != TransactionState.CIVIC_COMMITTED) {
            return assessment(
                    evidence,
                    AuditableEconomicActivityDecision.EXCLUDED_NOT_COMMITTED,
                    MoneyAmount.ZERO);
        }
        if (transaction.kind() != PaymentKind.PAYMENT) {
            throw new IllegalArgumentException(
                    "Evidence does not describe includable auditable economic activity");
        }
        long included = Math.min(
                transaction.amount().minorUnits(), evidence.referenceValue().minorUnits());
        return assessment(
                evidence,
                AuditableEconomicActivityDecision.INCLUDED,
                MoneyAmount.ofMinorUnits(included));
    }

    private static AuditableEconomicActivityAssessment assessment(
            AuditableEconomicActivityEvidence evidence,
            AuditableEconomicActivityDecision decision,
            MoneyAmount includedValue) {
        return new AuditableEconomicActivityAssessment(
                evidence.nationId(),
                evidence.transaction().transactionId(),
                decision,
                includedValue,
                evidence.subject(),
                evidence.subjectReference(),
                evidence.evidenceReference());
    }
}
