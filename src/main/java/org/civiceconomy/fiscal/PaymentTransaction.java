package org.civiceconomy.fiscal;

import java.util.Optional;
import java.util.UUID;

public record PaymentTransaction(
        UUID transactionId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID reservationId,
        AccountId sourceAccount,
        AccountId recipientAccount,
        MoneyAmount amount,
        PaymentKind kind,
        Optional<UUID> parentTransactionId,
        MoneyAmount refundedAmount,
        Optional<String> reason,
        TransactionState state) {}
