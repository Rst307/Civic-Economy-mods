package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTerritoryMaintenanceCycle(
        UUID cycleId,
        String serviceIdentity,
        String requestId,
        long startsAtEpochMillis,
        long endsAtEpochMillis,
        long openedAtEpochMillis) {}
