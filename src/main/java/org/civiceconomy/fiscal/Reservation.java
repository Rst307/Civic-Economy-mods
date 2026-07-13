package org.civiceconomy.fiscal;

import java.util.UUID;

public record Reservation(
        UUID reservationId,
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId sourceAccount,
        MoneyAmount amount,
        String purpose) {}
