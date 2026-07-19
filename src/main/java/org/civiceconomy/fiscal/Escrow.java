package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record Escrow(
        UUID escrowId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID reservationId,
        AccountId sourceAccount,
        MoneyAmount amount,
        MoneyAmount settledAmount,
        String externalObjectId,
        String purpose,
        Instant expiresAt,
        Optional<AccountId> requiredRecipientAccount,
        EscrowState state) {
    public MoneyAmount remainingAmount() {
        return amount.minus(settledAmount);
    }
}
