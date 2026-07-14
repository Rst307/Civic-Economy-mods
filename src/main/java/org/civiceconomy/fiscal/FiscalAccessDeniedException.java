package org.civiceconomy.fiscal;

public final class FiscalAccessDeniedException extends SecurityException {
    public FiscalAccessDeniedException(
            ServiceIdentity serviceIdentity, FiscalCapability capability, AccountId accountId) {
        super("Fiscal service " + serviceIdentity.value() + " lacks " + capability
                + " for " + accountId.value());
    }
}
