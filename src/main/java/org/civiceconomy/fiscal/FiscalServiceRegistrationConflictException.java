package org.civiceconomy.fiscal;

public final class FiscalServiceRegistrationConflictException extends IllegalStateException {
    public FiscalServiceRegistrationConflictException(ServiceIdentity serviceIdentity) {
        super("Fiscal service identity is already registered with different metadata: "
                + serviceIdentity.value());
    }
}
