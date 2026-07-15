package org.civiceconomy.territory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class TerritoryForceLoadRestrictionMirror {
    private final AtomicReference<Map<Target, TerritoryForceLoadRestriction>> restrictions =
            new AtomicReference<>(Map.of());

    public void replaceAll(List<TerritoryForceLoadRestriction> replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException(
                    "Territory Force-load Restriction snapshot cannot be null");
        }
        Map<Target, TerritoryForceLoadRestriction> indexed = new HashMap<>();
        for (TerritoryForceLoadRestriction restriction : replacement) {
            if (restriction == null) {
                throw new IllegalArgumentException(
                        "Territory Force-load Restriction snapshot cannot contain null values");
            }
            Target target = new Target(restriction.ftbTeamId(), restriction.position());
            TerritoryForceLoadRestriction conflict = indexed.putIfAbsent(target, restriction);
            if (conflict != null && !conflict.equals(restriction)) {
                throw new IllegalArgumentException(
                        "Territory Force-load Restriction snapshot contains a duplicate target");
            }
        }
        restrictions.set(Map.copyOf(indexed));
    }

    public boolean blocks(UUID ftbTeamId, TerritoryClaimPosition position) {
        if (ftbTeamId == null || position == null) {
            return false;
        }
        return restrictions.get().containsKey(new Target(ftbTeamId, position));
    }

    private record Target(UUID ftbTeamId, TerritoryClaimPosition position) {}
}
