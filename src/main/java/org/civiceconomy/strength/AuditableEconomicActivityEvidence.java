package org.civiceconomy.strength;

import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentTransaction;
import org.civiceconomy.nation.NationId;

public record AuditableEconomicActivityEvidence(
        NationId nationId,
        PaymentTransaction transaction,
        AuditableEconomicActivitySubject subject,
        String subjectReference,
        String payerController,
        String recipientController,
        MoneyAmount referenceValue,
        String evidenceReference) {
    public AuditableEconomicActivityEvidence {
        if (nationId == null || transaction == null || subject == null
                || subjectReference == null || subjectReference.isBlank()
                || payerController == null || payerController.isBlank()
                || recipientController == null || recipientController.isBlank()
                || referenceValue == null || referenceValue.minorUnits() == 0L
                || evidenceReference == null || evidenceReference.isBlank()) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity Evidence is incomplete");
        }
    }
}
