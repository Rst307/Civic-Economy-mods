package org.civiceconomy.fiscal;

public final class FiscalServiceOwnerMismatchException extends SecurityException {
    public FiscalServiceOwnerMismatchException(
            ServiceIdentity serviceIdentity, String registeredOwnerModId, String verifiedOwnerModId) {
        super("Fiscal service " + serviceIdentity.value() + " belongs to Mod "
                + registeredOwnerModId + ", not " + verifiedOwnerModId);
    }
}
