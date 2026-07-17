package org.civiceconomy.strength;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record RegisterAuditableEconomicActivityEvidence(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID paymentTransactionId,
        NationId nationId,
        AuditableEconomicActivitySubject subject,
        String subjectReference,
        String payerController,
        String recipientController,
        long referenceValueMinorUnits,
        String evidenceReference,
        long recordedAtEpochMillis) {
    public RegisterAuditableEconomicActivityEvidence {
        if (serviceIdentity == null || requestId == null || requestId.isBlank()
                || paymentTransactionId == null || nationId == null || subject == null
                || subjectReference == null || subjectReference.isBlank()
                || payerController == null || payerController.isBlank()
                || recipientController == null || recipientController.isBlank()
                || referenceValueMinorUnits <= 0L
                || evidenceReference == null || evidenceReference.isBlank()
                || recordedAtEpochMillis < 0L) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity registration is invalid");
        }
    }
}
