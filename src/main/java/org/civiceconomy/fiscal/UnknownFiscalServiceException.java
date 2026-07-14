package org.civiceconomy.fiscal;

public final class UnknownFiscalServiceException extends IllegalArgumentException {
    public UnknownFiscalServiceException(ServiceIdentity serviceIdentity) {
        super("Unknown fiscal service " + serviceIdentity.value());
    }
}
