package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationalIssuanceQuotaActivation(
        UUID activationId,
        String serviceIdentity,
        String requestId,
        UUID periodId,
        UUID nationId,
        UUID actorPlayerId,
        long activatedMinorUnits,
        String reason,
        long activatedAtEpochMillis) {}
