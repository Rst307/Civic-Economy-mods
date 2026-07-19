package org.civiceconomy.nation;

public record Capital(String dimensionId, int chunkX, int chunkZ) {
    public Capital {
        if (dimensionId == null || dimensionId.isBlank() || !dimensionId.contains(":")) {
            throw new IllegalArgumentException("Capital dimension must be a namespaced identifier");
        }
    }
}
