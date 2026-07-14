package org.civiceconomy.fiscal;

import java.time.Instant;

public record OpenEscrow(
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId sourceAccount,
        MoneyAmount amount,
        String externalObjectId,
        String purpose,
        Instant expiresAt) {
    public OpenEscrow {
        if (serviceIdentity == null || sourceAccount == null || amount == null || expiresAt == null) {
            throw new IllegalArgumentException("Escrow identity, source, amount, and expiry cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Escrow amount must be positive");
        }
        if (externalObjectId == null || externalObjectId.isBlank()) {
            throw new IllegalArgumentException("Escrow external object ID cannot be blank");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Escrow purpose cannot be blank");
        }
    }
}
