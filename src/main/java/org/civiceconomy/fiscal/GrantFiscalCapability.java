package org.civiceconomy.fiscal;

public record GrantFiscalCapability(
        ServiceIdentity administrator,
        String requestId,
        ServiceIdentity serviceIdentity,
        FiscalCapability capability,
        AccountId accountId,
        String reason) {
    public GrantFiscalCapability {
        if (administrator == null
                || serviceIdentity == null
                || capability == null
                || accountId == null) {
            throw new IllegalArgumentException("Fiscal grant identities, capability, and account cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Fiscal grant request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Fiscal grant reason cannot be blank");
        }
    }
}
