package org.civiceconomy.territory;

import java.time.Clock;

public final class TerritoryClaimPermitEventBridge {
    private final TerritoryClaimPermitMirror mirror;
    private final TerritoryClaimPermitConsumptionQueue consumptionQueue;
    private final Clock clock;

    public TerritoryClaimPermitEventBridge(
            TerritoryClaimPermitMirror mirror,
            TerritoryClaimPermitConsumptionQueue consumptionQueue,
            Clock clock) {
        if (mirror == null || consumptionQueue == null || clock == null) {
            throw new IllegalArgumentException(
                    "Territory Claim Permit event dependencies cannot be null");
        }
        this.mirror = mirror;
        this.consumptionQueue = consumptionQueue;
        this.clock = clock;
    }

    public boolean beforeClaim(TerritoryClaimTarget target) {
        return mirror.authorizes(target, clock.instant());
    }

    public boolean afterSuccessfulClaim(TerritoryClaimTarget target) {
        var permit = mirror.acquireAfterSuccessfulClaim(target, clock.instant());
        if (permit.isEmpty()) {
            return false;
        }
        consumptionQueue.submit(new TerritoryClaimPermitConsumptionIntent(
                permit.orElseThrow().permitId(), target, clock.instant()));
        return true;
    }
}
