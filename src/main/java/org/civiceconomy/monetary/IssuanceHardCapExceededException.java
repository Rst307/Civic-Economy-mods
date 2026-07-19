package org.civiceconomy.monetary;

public final class IssuanceHardCapExceededException extends IllegalStateException {
    public IssuanceHardCapExceededException() {
        super("Confirmed issuance would exceed the Issuance Hard Cap");
    }
}
