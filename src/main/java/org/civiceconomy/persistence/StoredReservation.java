package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredReservation(
        UUID reservationId,
        String serviceIdentity,
        String requestId,
        String sourceAccount,
        long amountMinorUnits,
        String purpose) {}
