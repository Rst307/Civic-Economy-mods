package org.civiceconomy.persistence;

import java.util.UUID;

public final class ReservationRemainderExceededException extends IllegalStateException {
    private final UUID reservationId;
    private final long requestedMinorUnits;
    private final long remainingMinorUnits;

    public ReservationRemainderExceededException(
            UUID reservationId, long requestedMinorUnits, long remainingMinorUnits) {
        super("Settlement exceeds Reservation remainder " + remainingMinorUnits);
        this.reservationId = reservationId;
        this.requestedMinorUnits = requestedMinorUnits;
        this.remainingMinorUnits = remainingMinorUnits;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public long requestedMinorUnits() {
        return requestedMinorUnits;
    }

    public long remainingMinorUnits() {
        return remainingMinorUnits;
    }
}
