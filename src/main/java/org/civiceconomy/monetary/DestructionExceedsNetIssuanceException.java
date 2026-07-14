package org.civiceconomy.monetary;

public final class DestructionExceedsNetIssuanceException extends IllegalStateException {
    public DestructionExceedsNetIssuanceException() {
        super("Permanent Destruction cannot exceed Cumulative Net Issuance");
    }
}
