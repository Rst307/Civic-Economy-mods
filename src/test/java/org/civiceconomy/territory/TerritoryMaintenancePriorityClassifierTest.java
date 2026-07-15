package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.civiceconomy.nation.Capital;
import org.junit.jupiter.api.Test;

class TerritoryMaintenancePriorityClassifierTest {
    @Test
    void classifiesCapitalFourWayCoreAndDisconnectedTerritoryConservatively() {
        TerritoryClaimPosition capital = claim("minecraft:overworld", 0, 0);
        TerritoryClaimPosition east = claim("minecraft:overworld", 1, 0);
        TerritoryClaimPosition diagonalChain = claim("minecraft:overworld", 2, 1);
        TerritoryClaimPosition enclave = claim("minecraft:overworld", 9, 9);
        TerritoryClaimPosition nether = claim("minecraft:the_nether", 0, 0);

        var priorities = new TerritoryMaintenancePriorityClassifier().classify(
                new Capital("minecraft:overworld", 0, 0),
                List.of(enclave, diagonalChain, capital, nether, east));

        assertEquals(TerritoryMaintenancePriority.CAPITAL, priorities.get(capital));
        assertEquals(
                TerritoryMaintenancePriority.CAPITAL_CONNECTED_CORE,
                priorities.get(east));
        assertEquals(
                TerritoryMaintenancePriority.ENCLAVE_OR_CROSS_DIMENSION,
                priorities.get(diagonalChain));
        assertEquals(
                TerritoryMaintenancePriority.ENCLAVE_OR_CROSS_DIMENSION,
                priorities.get(enclave));
        assertEquals(
                TerritoryMaintenancePriority.ENCLAVE_OR_CROSS_DIMENSION,
                priorities.get(nether));
    }

    @Test
    void missingCapitalClaimMakesEveryClaimLowPriority() {
        TerritoryClaimPosition ordinary = claim("minecraft:overworld", 1, 0);

        var priorities = new TerritoryMaintenancePriorityClassifier().classify(
                new Capital("minecraft:overworld", 0, 0), List.of(ordinary));

        assertEquals(
                TerritoryMaintenancePriority.ENCLAVE_OR_CROSS_DIMENSION,
                priorities.get(ordinary));
    }

    @Test
    void worldBorderCoordinateDoesNotOverflowAdjacency() {
        TerritoryClaimPosition capital = claim(
                "minecraft:overworld", Integer.MAX_VALUE, Integer.MIN_VALUE);

        var priorities = new TerritoryMaintenancePriorityClassifier().classify(
                new Capital(
                        "minecraft:overworld", Integer.MAX_VALUE, Integer.MIN_VALUE),
                List.of(capital));

        assertEquals(TerritoryMaintenancePriority.CAPITAL, priorities.get(capital));
    }

    private static TerritoryClaimPosition claim(String dimension, int chunkX, int chunkZ) {
        return new TerritoryClaimPosition(dimension, chunkX, chunkZ);
    }
}
