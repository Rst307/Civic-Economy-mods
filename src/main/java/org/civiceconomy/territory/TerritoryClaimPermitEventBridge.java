package org.civiceconomy.territory;

import java.time.Clock;

public final class TerritoryClaimPermitEventBridge {
    private final TerritoryClaimPermitMirror mirror;
    private final FreeClaimAuthorizationMirror freeClaims;
    private final TerritoryClaimPermitConsumptionQueue consumptionQueue;
    private final Clock clock;

    public TerritoryClaimPermitEventBridge(
            TerritoryClaimPermitMirror mirror,
            FreeClaimAuthorizationMirror freeClaims,
            TerritoryClaimPermitConsumptionQueue consumptionQueue,
            Clock clock) {
        if (mirror == null || freeClaims == null || consumptionQueue == null || clock == null) {
            throw new IllegalArgumentException(
                    "Territory Claim Permit event dependencies cannot be null");
        }
        this.mirror = mirror;
        this.freeClaims = freeClaims;
        this.consumptionQueue = consumptionQueue;
        this.clock = clock;
    }

    public boolean beforeClaim(TerritoryClaimTarget target) {
        var now = clock.instant();
        return mirror.authorizes(target, now) || freeClaims.authorizes(target, now);
    }

    public boolean afterSuccessfulClaim(TerritoryClaimTarget target) {
        var permit = mirror.acquireAfterSuccessfulClaim(target, clock.instant());
        if (permit.isEmpty()) {
            return freeClaims.acquireAfterSuccessfulClaim(target, clock.instant()).isPresent();
        }
        consumptionQueue.submit(new TerritoryClaimPermitConsumptionIntent(
                permit.orElseThrow().permitId(), target, clock.instant()));
        return true;
    }
}
