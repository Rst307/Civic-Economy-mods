package org.civiceconomy.territory;

import java.util.UUID;

public record CancelTerritoryClaimPermit(
        String requestId, UUID permitId, UUID actorPlayerId, String reason) {
    public CancelTerritoryClaimPermit {
        if (permitId == null || actorPlayerId == null) {
            throw new IllegalArgumentException("Territory Claim Permit cancellation identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim Permit cancellation request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Territory Claim Permit cancellation reason cannot be blank");
        }
    }
}
