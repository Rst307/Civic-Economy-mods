package org.civiceconomy.issuance;

import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record ActivateNationalIssuanceQuota(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID periodId,
        NationId nationId,
        UUID actorPlayerId,
        MoneyAmount amount,
        String reason) {
    public ActivateNationalIssuanceQuota {
        if (serviceIdentity == null
                || periodId == null
                || nationId == null
                || actorPlayerId == null
                || amount == null) {
            throw new IllegalArgumentException("National Issuance Quota activation cannot contain null values");
        }
        if (requestId == null || requestId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("National Issuance Quota activation values are invalid");
        }
    }
}
