package org.civiceconomy.territory;

import java.time.Instant;
import java.util.UUID;

public record FreeClaimAuthorization(
        UUID authorizationId, String requestId, TerritoryClaimTarget target, Instant expiresAt) {
    public FreeClaimAuthorization {
        if (authorizationId == null || target == null || expiresAt == null) {
            throw new IllegalArgumentException(
                    "Free Claim Authorization cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "Free Claim Authorization request ID cannot be blank");
        }
    }
}
