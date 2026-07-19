package org.civiceconomy.production;

import org.civiceconomy.territory.TerritoryClaimPosition;

public record FacilityCorePosition(
        String dimensionId, int blockX, int blockY, int blockZ) {
    public FacilityCorePosition {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("Facility core dimension cannot be blank");
        }
    }

    public TerritoryClaimPosition claim() {
        return new TerritoryClaimPosition(
                dimensionId, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
    }
}
