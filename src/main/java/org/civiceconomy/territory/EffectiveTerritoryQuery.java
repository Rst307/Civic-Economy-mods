package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.nation.NationId;

public final class EffectiveTerritoryQuery {
    private final TerritoryMaintenanceRegistry maintenance;
    private final TerritoryOwnershipSource ownership;

    public EffectiveTerritoryQuery(
            TerritoryMaintenanceRegistry maintenance, TerritoryOwnershipSource ownership) {
        if (maintenance == null || ownership == null) {
            throw new IllegalArgumentException(
                    "Effective Territory query dependencies cannot be null");
        }
        this.maintenance = maintenance;
        this.ownership = ownership;
    }

    public boolean isEffective(
            UUID cycleId,
            NationId nationId,
            String dimensionId,
            int chunkX,
            int chunkZ) {
        if (cycleId == null
                || nationId == null
                || dimensionId == null
                || dimensionId.isBlank()) {
            return false;
        }
        return ownerTeam(dimensionId, chunkX, chunkZ)
                .map(teamId -> isEffective(
                        cycleId, nationId, teamId, dimensionId, chunkX, chunkZ))
                .orElse(false);
    }

    public boolean isEffective(
            UUID cycleId,
            NationId nationId,
            UUID expectedFtbTeamId,
            String dimensionId,
            int chunkX,
            int chunkZ) {
        if (cycleId == null
                || nationId == null
                || expectedFtbTeamId == null
                || dimensionId == null
                || dimensionId.isBlank()) {
            return false;
        }
        return ownerTeam(dimensionId, chunkX, chunkZ)
                .filter(expectedFtbTeamId::equals)
                .filter(teamId -> maintenance.isEffective(
                        cycleId, nationId, teamId, dimensionId, chunkX, chunkZ))
                .isPresent();
    }

    private java.util.Optional<UUID> ownerTeam(
            String dimensionId, int chunkX, int chunkZ) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return java.util.Optional.empty();
        }
        return ownership.ownerTeam(dimensionId, chunkX, chunkZ);
    }
}
