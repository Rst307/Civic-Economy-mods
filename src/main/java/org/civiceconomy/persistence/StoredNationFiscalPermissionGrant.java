package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationFiscalPermissionGrant(
        UUID grantId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID actorPlayerId,
        UUID playerId,
        String permission,
        String reason,
        long grantedAtEpochMillis) {}
