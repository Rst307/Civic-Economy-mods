package org.civiceconomy.territory;

public record TerritoryMaintenanceObservedClaim(
        TerritoryClaimPosition position, boolean forceLoadRequested) {
    public TerritoryMaintenanceObservedClaim {
        if (position == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance observed Claim position cannot be null");
        }
    }
}
