package org.civiceconomy.fiscal;

import java.util.UUID;

public record PaymentTransaction(
        UUID transactionId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID reservationId,
        AccountId sourceAccount,
        AccountId recipientAccount,
        MoneyAmount amount,
        TransactionState state) {}
