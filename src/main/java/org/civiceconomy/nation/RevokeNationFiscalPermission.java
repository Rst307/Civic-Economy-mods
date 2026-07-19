package org.civiceconomy.nation;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record RevokeNationFiscalPermission(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID actorPlayerId,
        UUID grantId,
        String reason) {
    public RevokeNationFiscalPermission {
        if (serviceIdentity == null
                || nationId == null
                || actorPlayerId == null
                || grantId == null) {
            throw new IllegalArgumentException("Nation Fiscal Permission revocation cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Nation Fiscal Permission revocation request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Nation Fiscal Permission revocation reason cannot be blank");
        }
    }
}
