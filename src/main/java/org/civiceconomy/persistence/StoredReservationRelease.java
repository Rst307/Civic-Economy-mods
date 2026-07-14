package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredReservationRelease(
        UUID releaseId,
        String serviceIdentity,
        String requestId,
        UUID reservationId,
        String reason,
        long releasedAtEpochMillis) {}
