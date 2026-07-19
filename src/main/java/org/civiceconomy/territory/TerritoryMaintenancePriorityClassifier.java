package org.civiceconomy.territory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.civiceconomy.nation.Capital;

public final class TerritoryMaintenancePriorityClassifier {
    public Map<TerritoryClaimPosition, TerritoryMaintenancePriority> classify(
            Capital capital, List<TerritoryClaimPosition> claims) {
        if (capital == null
                || claims == null
                || claims.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance classification cannot contain null values");
        }
        Set<TerritoryClaimPosition> uniqueClaims = new HashSet<>(claims);
        if (uniqueClaims.size() != claims.size()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance classification contains duplicate claims");
        }
        TerritoryClaimPosition capitalClaim = new TerritoryClaimPosition(
                capital.dimensionId(), capital.chunkX(), capital.chunkZ());
        Set<TerritoryClaimPosition> connectedCore = connectedComponent(capitalClaim, uniqueClaims);
        Map<TerritoryClaimPosition, TerritoryMaintenancePriority> priorities = new HashMap<>();
        for (TerritoryClaimPosition claim : claims) {
            TerritoryMaintenancePriority priority;
            if (claim.equals(capitalClaim)) {
                priority = TerritoryMaintenancePriority.CAPITAL;
            } else if (!claim.dimensionId().equals(capital.dimensionId())) {
                priority = TerritoryMaintenancePriority.ENCLAVE_OR_CROSS_DIMENSION;
            } else if (connectedCore.contains(claim)) {
                priority = TerritoryMaintenancePriority.CAPITAL_CONNECTED_CORE;
            } else {
                priority = TerritoryMaintenancePriority.ENCLAVE_OR_CROSS_DIMENSION;
            }
            priorities.put(claim, priority);
        }
        return Map.copyOf(priorities);
    }

    private static Set<TerritoryClaimPosition> connectedComponent(
            TerritoryClaimPosition capitalClaim, Set<TerritoryClaimPosition> claims) {
        if (!claims.contains(capitalClaim)) {
            return Set.of();
        }
        Set<TerritoryClaimPosition> connected = new HashSet<>();
        ArrayDeque<TerritoryClaimPosition> pending = new ArrayDeque<>();
        connected.add(capitalClaim);
        pending.add(capitalClaim);
        while (!pending.isEmpty()) {
            TerritoryClaimPosition current = pending.removeFirst();
            for (TerritoryClaimPosition neighbor : neighbors(current)) {
                if (claims.contains(neighbor) && connected.add(neighbor)) {
                    pending.addLast(neighbor);
                }
            }
        }
        return Set.copyOf(connected);
    }

    private static List<TerritoryClaimPosition> neighbors(TerritoryClaimPosition claim) {
        List<TerritoryClaimPosition> neighbors = new ArrayList<>(4);
        if (claim.chunkX() < Integer.MAX_VALUE) {
            neighbors.add(new TerritoryClaimPosition(
                    claim.dimensionId(), claim.chunkX() + 1, claim.chunkZ()));
        }
        if (claim.chunkX() > Integer.MIN_VALUE) {
            neighbors.add(new TerritoryClaimPosition(
                    claim.dimensionId(), claim.chunkX() - 1, claim.chunkZ()));
        }
        if (claim.chunkZ() < Integer.MAX_VALUE) {
            neighbors.add(new TerritoryClaimPosition(
                    claim.dimensionId(), claim.chunkX(), claim.chunkZ() + 1));
        }
        if (claim.chunkZ() > Integer.MIN_VALUE) {
            neighbors.add(new TerritoryClaimPosition(
                    claim.dimensionId(), claim.chunkX(), claim.chunkZ() - 1));
        }
        return List.copyOf(neighbors);
    }
}
