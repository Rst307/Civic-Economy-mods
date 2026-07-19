package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationFiscalPermissionRevocation(
        UUID revocationId,
        UUID grantId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID actorPlayerId,
        String reason,
        long revokedAtEpochMillis) {}
