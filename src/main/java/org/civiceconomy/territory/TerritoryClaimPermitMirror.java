package org.civiceconomy.territory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TerritoryClaimPermitMirror {
    private final Map<TerritoryClaimTarget, TerritoryClaimPermit> ready =
            new ConcurrentHashMap<>();

    public void publish(TerritoryClaimPermit permit) {
        if (permit == null || permit.state() != TerritoryClaimPermitState.READY) {
            throw new IllegalArgumentException("Only a READY Territory Claim Permit can be published");
        }
        TerritoryClaimTarget target = target(permit);
        ready.compute(target, (ignored, existing) -> {
            if (existing != null && !existing.permitId().equals(permit.permitId())) {
                throw new IllegalStateException(
                        "A different READY Territory Claim Permit is already mirrored for the target");
            }
            return permit;
        });
    }

    public boolean authorizes(TerritoryClaimTarget target, Instant now) {
        if (target == null || now == null) {
            return false;
        }
        TerritoryClaimPermit permit = ready.get(target);
        return permit != null
                && permit.state() == TerritoryClaimPermitState.READY
                && now.isBefore(permit.expiresAt());
    }

    public Optional<TerritoryClaimPermit> acquireAfterSuccessfulClaim(
            TerritoryClaimTarget target, Instant now) {
        if (target == null || now == null) {
            return Optional.empty();
        }
        TerritoryClaimPermit[] acquired = new TerritoryClaimPermit[1];
        ready.computeIfPresent(target, (ignored, permit) -> {
            if (permit.state() != TerritoryClaimPermitState.READY
                    || !now.isBefore(permit.expiresAt())) {
                return permit;
            }
            acquired[0] = permit;
            return null;
        });
        return Optional.ofNullable(acquired[0]);
    }

    public void remove(TerritoryClaimPermit permit) {
        if (permit != null) {
            ready.remove(target(permit), permit);
        }
    }

    public void replaceAll(java.util.Collection<TerritoryClaimPermit> permits) {
        if (permits == null) {
            throw new IllegalArgumentException("Territory Claim Permit snapshot cannot be null");
        }
        Map<TerritoryClaimTarget, TerritoryClaimPermit> replacement = new java.util.HashMap<>();
        for (TerritoryClaimPermit permit : permits) {
            if (permit == null || permit.state() != TerritoryClaimPermitState.READY) {
                throw new IllegalArgumentException(
                        "Territory Claim Permit snapshot must contain only READY permits");
            }
            TerritoryClaimPermit previous = replacement.put(target(permit), permit);
            if (previous != null && !previous.permitId().equals(permit.permitId())) {
                throw new IllegalStateException(
                        "Territory Claim Permit snapshot contains duplicate READY targets");
            }
        }
        ready.clear();
        ready.putAll(replacement);
    }

    private static TerritoryClaimTarget target(TerritoryClaimPermit permit) {
        return new TerritoryClaimTarget(
                permit.nationId(),
                permit.ftbTeamId(),
                permit.actorPlayerId(),
                permit.dimensionId(),
                permit.chunkX(),
                permit.chunkZ());
    }
}
