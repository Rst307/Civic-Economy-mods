package org.civiceconomy.production;

import org.civiceconomy.territory.TerritoryClaimPosition;

public record FacilityAccountingInterfacePosition(
        String dimensionId, int blockX, int blockY, int blockZ) {
    public FacilityAccountingInterfacePosition {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface dimension cannot be blank");
        }
    }

    public TerritoryClaimPosition claim() {
        return new TerritoryClaimPosition(
                dimensionId, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
    }
}
