package org.civiceconomy.fiscal;

import java.util.UUID;

public record ExpireEscrow(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID escrowId) {
    public ExpireEscrow {
        if (serviceIdentity == null || escrowId == null) {
            throw new IllegalArgumentException("Escrow expiry identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
    }
}
