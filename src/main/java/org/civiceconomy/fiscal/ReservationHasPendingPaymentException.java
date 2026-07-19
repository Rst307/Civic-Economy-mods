package org.civiceconomy.fiscal;

import java.util.UUID;

public final class ReservationHasPendingPaymentException extends IllegalStateException {
    public ReservationHasPendingPaymentException(UUID reservationId) {
        super("Reservation " + reservationId + " has an incomplete payment and cannot be released");
    }
}
