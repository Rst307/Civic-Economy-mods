package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredRegisteredFacilityStateTransition(
        UUID transitionId,
        UUID facilityId,
        String serviceIdentity,
        String fromState,
        String toState,
        String reason,
        long transitionedAtEpochMillis) {}
