package org.civiceconomy.territory;

import org.civiceconomy.nation.NationEffectiveCitizenPopulation;

public final class TerritoryFreeAllocationPolicy {
    private final int baseChunks;
    private final int chunksPerEffectiveCitizen;

    public TerritoryFreeAllocationPolicy(int baseChunks, int chunksPerEffectiveCitizen) {
        if (baseChunks < 0 || chunksPerEffectiveCitizen < 0) {
            throw new IllegalArgumentException("Territory Free Allocation policy cannot be negative");
        }
        this.baseChunks = baseChunks;
        this.chunksPerEffectiveCitizen = chunksPerEffectiveCitizen;
    }

    public TerritoryFreeAllocation calculate(NationEffectiveCitizenPopulation population) {
        if (population == null) {
            throw new IllegalArgumentException("Territory Free Allocation population cannot be null");
        }
        int effectiveCitizens = population.effectiveCitizenCount();
        int populationChunks = Math.multiplyExact(effectiveCitizens, chunksPerEffectiveCitizen);
        return new TerritoryFreeAllocation(
                population.nationId(),
                baseChunks,
                effectiveCitizens,
                chunksPerEffectiveCitizen,
                Math.addExact(baseChunks, populationChunks));
    }
}
