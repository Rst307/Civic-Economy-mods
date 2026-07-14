package org.civiceconomy.fiscal;

import java.util.UUID;

public record RevokeFiscalCapability(
        ServiceIdentity administrator,
        String requestId,
        UUID grantId,
        String reason) {
    public RevokeFiscalCapability {
        if (administrator == null || grantId == null) {
            throw new IllegalArgumentException("Fiscal revocation administrator and grant cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Fiscal revocation request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Fiscal revocation reason cannot be blank");
        }
    }
}
