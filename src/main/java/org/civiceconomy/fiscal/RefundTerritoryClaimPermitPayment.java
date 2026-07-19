package org.civiceconomy.fiscal;

import java.util.UUID;

public record RefundTerritoryClaimPermitPayment(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID permitId,
        String reason) {
    public RefundTerritoryClaimPermitPayment {
        if (serviceIdentity == null || permitId == null) {
            throw new IllegalArgumentException("Territory Claim Permit refund identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim Permit refund request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Territory Claim Permit refund reason cannot be blank");
        }
    }
}
