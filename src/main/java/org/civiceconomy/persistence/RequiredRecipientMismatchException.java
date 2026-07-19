package org.civiceconomy.persistence;

import java.util.UUID;

public final class RequiredRecipientMismatchException extends RuntimeException {
    private final UUID reservationId;
    private final String expectedRecipient;
    private final String actualRecipient;

    public RequiredRecipientMismatchException(
            UUID reservationId, String expectedRecipient, String actualRecipient) {
        this.reservationId = reservationId;
        this.expectedRecipient = expectedRecipient;
        this.actualRecipient = actualRecipient;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public String expectedRecipient() {
        return expectedRecipient;
    }

    public String actualRecipient() {
        return actualRecipient;
    }
}
