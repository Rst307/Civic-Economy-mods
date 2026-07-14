package org.civiceconomy.territory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.civiceconomy.nation.NationId;

public final class FreeClaimAuthorizationMirror {
    private final Map<TerritoryClaimTarget, FreeClaimAuthorization> pending =
            new ConcurrentHashMap<>();
    private final Map<String, FreeClaimAuthorization> byRequest =
            new ConcurrentHashMap<>();
    private final java.util.Set<String> spentRequests =
            ConcurrentHashMap.newKeySet();

    public void publish(FreeClaimAuthorization authorization) {
        if (authorization == null) {
            throw new IllegalArgumentException("Free Claim Authorization cannot be null");
        }
        if (spentRequests.contains(authorization.requestId())) {
            throw new IllegalStateException(
                    "Free Claim Authorization request is already consumed or expired");
        }
        FreeClaimAuthorization replay = byRequest.putIfAbsent(
                authorization.requestId(), authorization);
        if (replay != null && !replay.equals(authorization)) {
            throw new IllegalStateException(
                    "Free Claim Authorization request ID has different values");
        }
        pending.compute(authorization.target(), (ignored, existing) -> {
            if (existing != null
                    && !existing.authorizationId().equals(authorization.authorizationId())) {
                throw new IllegalStateException(
                        "A different Free Claim Authorization already exists for the target");
            }
            return authorization;
        });
    }

    public Optional<FreeClaimAuthorization> findByRequest(String requestId, Instant now) {
        if (requestId == null || requestId.isBlank() || now == null) {
            return Optional.empty();
        }
        FreeClaimAuthorization authorization = byRequest.get(requestId);
        if (authorization == null) {
            return Optional.empty();
        }
        if (!now.isBefore(authorization.expiresAt())) {
            expire(authorization);
            return Optional.empty();
        }
        return Optional.of(authorization);
    }

    public Optional<FreeClaimAuthorization> requireActiveReplay(
            String requestId, Instant now) {
        if (spentRequests.contains(requestId)) {
            throw new IllegalStateException(
                    "Free Claim Authorization request is already consumed or expired");
        }
        Optional<FreeClaimAuthorization> replay = findByRequest(requestId, now);
        if (spentRequests.contains(requestId)) {
            throw new IllegalStateException(
                    "Free Claim Authorization request is already consumed or expired");
        }
        return replay;
    }

    public boolean authorizes(TerritoryClaimTarget target, Instant now) {
        if (target == null || now == null) {
            return false;
        }
        FreeClaimAuthorization authorization = pending.get(target);
        if (authorization == null) {
            return false;
        }
        if (!now.isBefore(authorization.expiresAt())) {
            expire(authorization);
            return false;
        }
        return true;
    }

    public Optional<FreeClaimAuthorization> acquireAfterSuccessfulClaim(
            TerritoryClaimTarget target, Instant now) {
        if (target == null || now == null) {
            return Optional.empty();
        }
        FreeClaimAuthorization[] acquired = new FreeClaimAuthorization[1];
        pending.computeIfPresent(target, (ignored, authorization) -> {
            if (!now.isBefore(authorization.expiresAt())) {
                byRequest.remove(authorization.requestId(), authorization);
                spentRequests.add(authorization.requestId());
                return null;
            }
            acquired[0] = authorization;
            byRequest.remove(authorization.requestId(), authorization);
            spentRequests.add(authorization.requestId());
            return null;
        });
        return Optional.ofNullable(acquired[0]);
    }

    public int pendingCount(NationId nationId, Instant now) {
        if (nationId == null || now == null) {
            throw new IllegalArgumentException(
                    "Free Claim Authorization count cannot contain null values");
        }
        pending.forEach((target, authorization) -> {
            if (!now.isBefore(authorization.expiresAt())) {
                expire(authorization);
            }
        });
        return Math.toIntExact(pending.values().stream()
                .filter(authorization -> authorization.target().nationId().equals(nationId))
                .count());
    }

    public void clear() {
        pending.clear();
        byRequest.clear();
        spentRequests.clear();
    }

    private void expire(FreeClaimAuthorization authorization) {
        pending.remove(authorization.target(), authorization);
        byRequest.remove(authorization.requestId(), authorization);
        spentRequests.add(authorization.requestId());
    }
}
