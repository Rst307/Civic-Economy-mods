package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.junit.jupiter.api.Test;

class TerritoryMaintenancePriorityPolicyTest {
    @Test
    void fundsOnlyTheDeterministicHighestPriorityPrefix() {
        TerritoryMaintenanceCandidate capital = candidate(
                "b0000000-0000-0000-0000-000000000001",
                TerritoryMaintenancePriority.CAPITAL,
                "minecraft:overworld",
                0,
                0,
                100L);
        TerritoryMaintenanceCandidate core = candidate(
                "b0000000-0000-0000-0000-000000000002",
                TerritoryMaintenancePriority.CAPITAL_CONNECTED_CORE,
                "minecraft:overworld",
                1,
                0,
                80L);
        TerritoryMaintenanceCandidate infrastructure = candidate(
                "b0000000-0000-0000-0000-000000000003",
                TerritoryMaintenancePriority.VALID_INFRASTRUCTURE,
                "minecraft:overworld",
                2,
                0,
                60L);
        TerritoryMaintenanceCandidate ordinary = candidate(
                "b0000000-0000-0000-0000-000000000004",
                TerritoryMaintenancePriority.ORDINARY,
                "minecraft:overworld",
                3,
                0,
                10L);

        TerritoryMaintenancePriorityDecision decision =
                new TerritoryMaintenancePriorityPolicy().select(
                        List.of(ordinary, infrastructure, capital, core),
                        MoneyAmount.ofMinorUnits(200L));

        assertEquals(List.of(capital, core), decision.funded());
        assertEquals(List.of(infrastructure, ordinary), decision.suspended());
        assertEquals(MoneyAmount.ofMinorUnits(180L), decision.fundedAmount());
        assertEquals(MoneyAmount.ofMinorUnits(20L), decision.unspentAmount());
    }

    @Test
    void samePriorityUsesStableDimensionAndCoordinateOrder() {
        TerritoryMaintenanceCandidate later = candidate(
                "b0000000-0000-0000-0000-000000000005",
                TerritoryMaintenancePriority.ORDINARY,
                "minecraft:overworld",
                9,
                0,
                50L);
        TerritoryMaintenanceCandidate earlier = candidate(
                "b0000000-0000-0000-0000-000000000006",
                TerritoryMaintenancePriority.ORDINARY,
                "minecraft:overworld",
                4,
                0,
                50L);

        TerritoryMaintenancePriorityDecision decision =
                new TerritoryMaintenancePriorityPolicy().select(
                        List.of(later, earlier), MoneyAmount.ofMinorUnits(50L));

        assertEquals(List.of(earlier), decision.funded());
        assertEquals(List.of(later), decision.suspended());
    }

    @Test
    void zeroDueClaimRemainsFundedAfterARequiredPaymentGap() {
        TerritoryMaintenanceCandidate unaffordableCore = candidate(
                "b0000000-0000-0000-0000-000000000007",
                TerritoryMaintenancePriority.CAPITAL_CONNECTED_CORE,
                "minecraft:overworld",
                1,
                0,
                100L);
        TerritoryMaintenanceCandidate freeEnclave = candidate(
                "b0000000-0000-0000-0000-000000000008",
                TerritoryMaintenancePriority.ENCLAVE_OR_CROSS_DIMENSION,
                "minecraft:the_nether",
                2,
                0,
                0L);

        TerritoryMaintenancePriorityDecision decision =
                new TerritoryMaintenancePriorityPolicy().select(
                        List.of(freeEnclave, unaffordableCore), MoneyAmount.ZERO);

        assertEquals(List.of(freeEnclave), decision.funded());
        assertEquals(List.of(unaffordableCore), decision.suspended());
        assertEquals(MoneyAmount.ZERO, decision.fundedAmount());
        assertEquals(MoneyAmount.ZERO, decision.unspentAmount());
    }

    private static TerritoryMaintenanceCandidate candidate(
            String assessmentId,
            TerritoryMaintenancePriority priority,
            String dimensionId,
            int chunkX,
            int chunkZ,
            long due) {
        return new TerritoryMaintenanceCandidate(
                UUID.fromString(assessmentId),
                priority,
                dimensionId,
                chunkX,
                chunkZ,
                MoneyAmount.ofMinorUnits(due));
    }
}
