package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record NationFiscalPermissionRevocation(
        UUID revocationId,
        UUID grantId,
        NationId nationId,
        UUID actorPlayerId,
        String reason,
        Instant revokedAt) {
    public NationFiscalPermissionRevocation {
        if (revocationId == null
                || grantId == null
                || nationId == null
                || actorPlayerId == null
                || reason == null
                || revokedAt == null) {
            throw new IllegalArgumentException("Nation Fiscal Permission revocation cannot contain null values");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Nation Fiscal Permission revocation reason cannot be blank");
        }
    }
}
