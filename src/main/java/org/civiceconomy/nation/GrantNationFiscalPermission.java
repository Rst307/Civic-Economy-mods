package org.civiceconomy.nation;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record GrantNationFiscalPermission(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID actorPlayerId,
        UUID playerId,
        NationFiscalPermission permission,
        String reason) {
    public GrantNationFiscalPermission {
        if (serviceIdentity == null
                || nationId == null
                || actorPlayerId == null
                || playerId == null
                || permission == null) {
            throw new IllegalArgumentException("Nation Fiscal Permission grant cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Nation Fiscal Permission request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Nation Fiscal Permission reason cannot be blank");
        }
    }
}
