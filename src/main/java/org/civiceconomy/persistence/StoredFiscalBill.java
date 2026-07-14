package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFiscalBill(
        UUID billId,
        String serviceIdentity,
        String requestId,
        String payerAccount,
        String beneficiaryAccount,
        long amountMinorUnits,
        String kind,
        String purpose,
        long dueAtEpochMillis,
        UUID escrowId,
        long settledMinorUnits,
        String state) {}
