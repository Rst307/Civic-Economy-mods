package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationApplication(
        UUID applicationId,
        String serviceIdentity,
        String requestId,
        UUID ftbTeamId,
        UUID applicantPlayerId,
        long createdAtEpochMillis,
        long expiresAtEpochMillis,
        String state) {}
