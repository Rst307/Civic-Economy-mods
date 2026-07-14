package org.civiceconomy.persistence;

import java.util.UUID;

public final class InactiveReservationException extends IllegalStateException {
    private final UUID reservationId;
    private final String state;

    public InactiveReservationException(UUID reservationId, String state) {
        super("Reservation is not active: " + reservationId + "; state=" + state);
        this.reservationId = reservationId;
        this.state = state;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public String state() {
        return state;
    }
}
