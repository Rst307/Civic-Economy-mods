package org.civiceconomy.fiscal;

import java.util.UUID;

public record ReleaseReservation(
        ServiceIdentity serviceIdentity, String requestId, UUID reservationId, String reason) {
    public ReleaseReservation {
        if (serviceIdentity == null || reservationId == null) {
            throw new IllegalArgumentException("Reservation release identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reservation release reason cannot be blank");
        }
    }
}
