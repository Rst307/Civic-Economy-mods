package org.civiceconomy.territory;

import org.civiceconomy.nation.NationId;

public record TerritoryFreeAllocation(
        NationId nationId,
        int baseChunks,
        int effectiveCitizenCount,
        int chunksPerEffectiveCitizen,
        int totalFreeChunks) {
    public TerritoryFreeAllocation {
        if (nationId == null) {
            throw new IllegalArgumentException("Territory Free Allocation Nation cannot be null");
        }
        if (baseChunks < 0
                || effectiveCitizenCount < 0
                || chunksPerEffectiveCitizen < 0
                || totalFreeChunks < 0) {
            throw new IllegalArgumentException("Territory Free Allocation values cannot be negative");
        }
    }
}
