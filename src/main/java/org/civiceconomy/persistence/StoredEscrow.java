package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredEscrow(
        UUID escrowId,
        String serviceIdentity,
        String requestId,
        UUID reservationId,
        String sourceAccount,
        long amountMinorUnits,
        long settledMinorUnits,
        String externalObjectId,
        String purpose,
        long expiresAtEpochMillis,
        String state) {}
