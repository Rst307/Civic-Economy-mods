package org.civiceconomy.fiscal;

import java.util.UUID;

public record Reservation(
        UUID reservationId,
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId sourceAccount,
        MoneyAmount amount,
        MoneyAmount settledAmount,
        String purpose,
        ReservationState state) {
    public MoneyAmount remainingAmount() {
        return amount.minus(settledAmount);
    }
}
