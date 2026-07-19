package org.civiceconomy.fiscal;

public final class FiscalServiceIdentityMismatchException extends SecurityException {
    public FiscalServiceIdentityMismatchException(
            ServiceIdentity sessionIdentity, ServiceIdentity requestedIdentity) {
        super("Fiscal session for " + sessionIdentity.value()
                + " cannot submit identity " + requestedIdentity.value());
    }
}
