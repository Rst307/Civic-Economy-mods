package org.civiceconomy.fiscal;

import java.util.UUID;

public final class InsufficientReservationRemainderException extends IllegalStateException {
    public InsufficientReservationRemainderException(
            UUID reservationId, MoneyAmount requested, MoneyAmount remaining) {
        super("Reservation " + reservationId + " has " + remaining.minorUnits()
                + " minor units remaining; requested " + requested.minorUnits());
    }
}
