package org.civiceconomy.fiscal;

import java.util.UUID;

public record ReservationRelease(
        UUID releaseId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID reservationId,
        String reason,
        long releasedAtEpochMillis) {
    public ReservationRelease {
        if (releaseId == null || serviceIdentity == null || reservationId == null) {
            throw new IllegalArgumentException("Reservation release cannot contain null identity values");
        }
        if (requestId == null || requestId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reservation release text cannot be blank");
        }
        if (releasedAtEpochMillis < 0) {
            throw new IllegalArgumentException("Reservation release time cannot be negative");
        }
    }
}
