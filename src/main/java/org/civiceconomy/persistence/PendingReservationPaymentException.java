package org.civiceconomy.persistence;

import java.util.UUID;

public final class PendingReservationPaymentException extends IllegalStateException {
    private final UUID reservationId;

    public PendingReservationPaymentException(UUID reservationId) {
        super("Reservation has an incomplete payment: " + reservationId);
        this.reservationId = reservationId;
    }

    public UUID reservationId() {
        return reservationId;
    }
}
