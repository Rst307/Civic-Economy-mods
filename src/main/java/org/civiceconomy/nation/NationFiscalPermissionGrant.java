package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record NationFiscalPermissionGrant(
        UUID grantId,
        NationId nationId,
        UUID playerId,
        NationFiscalPermission permission,
        UUID actorPlayerId,
        String reason,
        Instant grantedAt) {
    public NationFiscalPermissionGrant {
        if (grantId == null
                || nationId == null
                || playerId == null
                || permission == null
                || actorPlayerId == null
                || reason == null
                || grantedAt == null) {
            throw new IllegalArgumentException("Nation Fiscal Permission grant cannot contain null values");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Nation Fiscal Permission grant reason cannot be blank");
        }
    }
}
