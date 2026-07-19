package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredAuditableEconomicActivityEvidence(
        UUID evidenceId,
        String serviceIdentity,
        String requestId,
        UUID paymentTransactionId,
        UUID nationId,
        String subject,
        String subjectReference,
        String payerController,
        String recipientController,
        long referenceValueMinorUnits,
        String decision,
        long includedValueMinorUnits,
        String evidenceReference,
        long recordedAtEpochMillis) {}
