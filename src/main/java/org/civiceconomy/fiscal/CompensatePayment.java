package org.civiceconomy.fiscal;

import java.util.UUID;

public record CompensatePayment(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID transactionId,
        String reason) {
    public CompensatePayment {
        if (serviceIdentity == null || transactionId == null) {
            throw new IllegalArgumentException("Compensation identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Compensation reason cannot be blank");
        }
    }
}
