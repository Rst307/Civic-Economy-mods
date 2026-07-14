package org.civiceconomy.fiscal;

import java.util.UUID;

public final class ReservationNotActiveException extends IllegalStateException {
    public ReservationNotActiveException(UUID reservationId, String state) {
        super("Reservation " + reservationId + " is not active; state=" + state);
    }
}
