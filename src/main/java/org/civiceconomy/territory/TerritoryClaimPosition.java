package org.civiceconomy.territory;

public record TerritoryClaimPosition(String dimensionId, int chunkX, int chunkZ) {
    public TerritoryClaimPosition {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim dimension cannot be blank");
        }
    }
}
