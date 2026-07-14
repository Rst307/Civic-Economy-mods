package org.civiceconomy.fiscal;

import java.util.UUID;

public record FundFiscalBill(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID billId) {
    public FundFiscalBill {
        if (serviceIdentity == null || billId == null) {
            throw new IllegalArgumentException("Fiscal Bill funding identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
    }
}
