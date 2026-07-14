package org.civiceconomy.territory;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface TerritoryOwnershipSource {
    Optional<UUID> ownerTeam(String dimensionId, int chunkX, int chunkZ);
}
