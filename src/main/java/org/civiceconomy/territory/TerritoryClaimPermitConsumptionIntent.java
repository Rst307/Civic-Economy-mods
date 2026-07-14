package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;

public record TerritoryClaimPermitConsumptionIntent(
        UUID permitId, TerritoryClaimTarget target, Instant claimedAt) {
    public TerritoryClaimPermitConsumptionIntent {
        if (permitId == null || target == null || claimedAt == null) {
            throw new IllegalArgumentException(
                    "Territory Claim Permit consumption intent cannot contain null values");
        }
    }
}
