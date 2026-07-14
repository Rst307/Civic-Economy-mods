package org.civiceconomy.fiscal;

public final class FiscalGrantConflictException extends IllegalStateException {
    public FiscalGrantConflictException(ServiceIdentity administrator, String requestId) {
        super("Fiscal capability grant request conflicts with its durable result: "
                + administrator.value() + "/" + requestId);
    }
}
