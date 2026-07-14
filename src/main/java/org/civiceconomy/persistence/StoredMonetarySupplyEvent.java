package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredMonetarySupplyEvent(
        UUID eventId,
        String serviceIdentity,
        String requestId,
        String changeKind,
        long amountMinorUnits,
        String externalReference,
        String reason,
        long confirmedAtEpochMillis) {}
