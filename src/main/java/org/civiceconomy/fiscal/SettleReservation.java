package org.civiceconomy.fiscal;

import java.util.UUID;

public record SettleReservation(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID reservationId,
        AccountId recipientAccount,
        MoneyAmount amount) {
    public SettleReservation {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Settlement amount must be positive");
        }
    }
}
