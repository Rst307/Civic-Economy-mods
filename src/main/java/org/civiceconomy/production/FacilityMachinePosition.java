package org.civiceconomy.production;

import org.civiceconomy.territory.TerritoryClaimPosition;

public record FacilityMachinePosition(
        String dimensionId, int blockX, int blockY, int blockZ) {
    public FacilityMachinePosition {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("Facility machine dimension cannot be blank");
        }
    }

    public TerritoryClaimPosition claim() {
        return new TerritoryClaimPosition(
                dimensionId, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
    }
}
