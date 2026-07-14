package org.civiceconomy.fiscal;

import java.util.UUID;

public final class PaymentRecipientMismatchException extends RuntimeException {
    private final UUID reservationId;
    private final AccountId expectedRecipient;
    private final AccountId actualRecipient;

    public PaymentRecipientMismatchException(
            UUID reservationId, AccountId expectedRecipient, AccountId actualRecipient) {
        super("Reservation " + reservationId + " requires recipient " + expectedRecipient
                + " but payment targeted " + actualRecipient);
        this.reservationId = reservationId;
        this.expectedRecipient = expectedRecipient;
        this.actualRecipient = actualRecipient;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public AccountId expectedRecipient() {
        return expectedRecipient;
    }

    public AccountId actualRecipient() {
        return actualRecipient;
    }
}
