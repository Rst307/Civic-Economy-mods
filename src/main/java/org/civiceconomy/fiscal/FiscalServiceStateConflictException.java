package org.civiceconomy.fiscal;

public final class FiscalServiceStateConflictException extends IllegalStateException {
    public FiscalServiceStateConflictException(ServiceIdentity administrator, String requestId) {
        super("Fiscal service state request conflicts with its durable result: "
                + administrator.value() + "/" + requestId);
    }
}
