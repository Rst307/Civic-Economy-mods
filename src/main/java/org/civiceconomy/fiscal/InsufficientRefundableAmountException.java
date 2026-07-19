package org.civiceconomy.fiscal;

import java.util.UUID;

public final class InsufficientRefundableAmountException extends IllegalStateException {
    public InsufficientRefundableAmountException(
            UUID originalTransactionId, MoneyAmount requested, MoneyAmount refundable) {
        super("Payment " + originalTransactionId + " has " + refundable.minorUnits()
                + " minor units refundable; requested " + requested.minorUnits());
    }
}
