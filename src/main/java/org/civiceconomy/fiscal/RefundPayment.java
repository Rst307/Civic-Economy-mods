package org.civiceconomy.fiscal;

import java.util.UUID;

public record RefundPayment(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID originalTransactionId,
        MoneyAmount amount,
        String reason) {
    public RefundPayment {
        if (serviceIdentity == null || originalTransactionId == null || amount == null) {
            throw new IllegalArgumentException("Refund identity and amount cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Refund reason cannot be blank");
        }
    }
}
