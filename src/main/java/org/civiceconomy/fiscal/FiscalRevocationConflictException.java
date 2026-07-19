package org.civiceconomy.fiscal;

public final class FiscalRevocationConflictException extends IllegalStateException {
    public FiscalRevocationConflictException(ServiceIdentity administrator, String requestId) {
        super("Fiscal capability revocation request conflicts with its durable result: "
                + administrator.value() + "/" + requestId);
    }
}
