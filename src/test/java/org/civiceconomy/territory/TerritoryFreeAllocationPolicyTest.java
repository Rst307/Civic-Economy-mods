package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.EffectiveCitizenContribution;
import org.civiceconomy.nation.NationEffectiveCitizenPopulation;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class TerritoryFreeAllocationPolicyTest {
    @Test
    void usesEffectiveCitizenCountInsteadOfFractionalEquivalentOrFtbMembership() {
        NationId nationId = new NationId(
                UUID.fromString("69084983-1cb3-4c37-878d-9ba397d49a42"));
        NationEffectiveCitizenPopulation population = new NationEffectiveCitizenPopulation(
                nationId,
                Instant.parse("2026-07-14T12:00:00Z"),
                List.of(
                        contribution(nationId, "48c4b26c-3f9b-4c09-ab07-f1ea4f916fb6", 1D),
                        contribution(nationId, "554e9062-f18a-4097-9462-693236494c62", 0.25D),
                        contribution(nationId, "ee02024b-349f-4d0f-afdd-c86e3ae3a46c", 0D)));
        TerritoryFreeAllocationPolicy policy = new TerritoryFreeAllocationPolicy(9, 4);

        TerritoryFreeAllocation allocation = policy.calculate(population);

        assertEquals(nationId, allocation.nationId());
        assertEquals(9, allocation.baseChunks());
        assertEquals(2, allocation.effectiveCitizenCount());
        assertEquals(4, allocation.chunksPerEffectiveCitizen());
        assertEquals(17, allocation.totalFreeChunks());
    }

    private static EffectiveCitizenContribution contribution(
            NationId nationId, String playerId, double contribution) {
        return new EffectiveCitizenContribution(
                UUID.fromString(playerId),
                nationId,
                contribution == 0D ? 0L : 60_000L,
                contribution);
    }
}
