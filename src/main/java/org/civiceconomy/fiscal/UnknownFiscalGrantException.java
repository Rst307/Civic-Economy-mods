package org.civiceconomy.fiscal;

import java.util.UUID;

public final class UnknownFiscalGrantException extends IllegalArgumentException {
    public UnknownFiscalGrantException(UUID grantId) {
        super("Unknown fiscal capability grant " + grantId);
    }
}
