package org.civiceconomy.fiscal;

import java.util.UUID;

public record ExternalPayment(
        UUID transactionId,
        AccountId sourceAccount,
        AccountId recipientAccount,
        MoneyAmount amount) {}
