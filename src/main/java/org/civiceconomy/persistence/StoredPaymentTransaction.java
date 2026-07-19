package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredPaymentTransaction(
        UUID transactionId,
        String serviceIdentity,
        String requestId,
        UUID reservationId,
        String sourceAccount,
        String recipientAccount,
        long amountMinorUnits,
        String kind,
        UUID parentTransactionId,
        long refundedMinorUnits,
        String reason,
        String state) {}
