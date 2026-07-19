package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFacilityAccountingBaseline(
        UUID baselineId,
        String serviceIdentity,
        String requestId,
        UUID facilityId,
        UUID interfaceId,
        String createVersion,
        UUID actorPlayerId,
        String state,
        String reason,
        long capturedAtEpochMillis,
        Long activatedAtEpochMillis) {}
