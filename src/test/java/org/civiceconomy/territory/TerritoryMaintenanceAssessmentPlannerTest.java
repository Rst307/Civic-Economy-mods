package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.Capital;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class TerritoryMaintenanceAssessmentPlannerTest {
    private static final NationId NATION =
            new NationId(UUID.fromString("9a4ac320-48bf-41ad-a130-df099630fa37"));
    private static final UUID TEAM =
            UUID.fromString("654b7f27-a407-42ac-8126-9dc2cd0187fc");

    @Test
    void freeAllocationProtectsPriorityThenChargesMultiplierAndForceLoad() {
        var planner = new TerritoryMaintenanceAssessmentPlanner();
        var capital = observed("minecraft:overworld", 0, 0, false);
        var core = observed("minecraft:overworld", 1, 0, true);
        var enclave = observed("minecraft:overworld", 9, 9, false);

        List<TerritoryMaintenanceClaimSnapshot> assessments = planner.plan(
                NATION,
                TEAM,
                new Capital("minecraft:overworld", 0, 0),
                List.of(enclave, core, capital),
                new TerritoryFreeAllocation(NATION, 1, 1, 0, 1),
                policy(101L));

        assertEquals(
                List.of(
                        TerritoryMaintenancePriority.CAPITAL,
                        TerritoryMaintenancePriority.CAPITAL_CONNECTED_CORE,
                        TerritoryMaintenancePriority.ENCLAVE_OR_CROSS_DIMENSION),
                assessments.stream().map(TerritoryMaintenanceClaimSnapshot::priority).toList());
        assertEquals(
                List.of(0L, 126L, 152L),
                assessments.stream()
                        .map(TerritoryMaintenanceClaimSnapshot::maintenanceDueMinorUnits)
                        .toList());
    }

    @Test
    void mismatchedNationAllocationFailsBeforePlanning() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerritoryMaintenanceAssessmentPlanner().plan(
                        NATION,
                        TEAM,
                        new Capital("minecraft:overworld", 0, 0),
                        List.of(observed("minecraft:overworld", 0, 0, false)),
                        new TerritoryFreeAllocation(
                                NationId.create(), 1, 0, 0, 1),
                        policy(100L)));
    }

    private static TerritoryMaintenanceObservedClaim observed(
            String dimension, int chunkX, int chunkZ, boolean forceLoaded) {
        return new TerritoryMaintenanceObservedClaim(
                new TerritoryClaimPosition(dimension, chunkX, chunkZ), forceLoaded);
    }

    private static TerritoryMaintenancePolicyVersion policy(long baseMaintenance) {
        return new TerritoryMaintenancePolicyVersion(
                UUID.randomUUID(),
                Duration.ofDays(7),
                baseMaintenance,
                15_000,
                25L,
                30L,
                Duration.ofDays(14),
                6_000,
                Instant.EPOCH,
                "console",
                "Test policy",
                Instant.EPOCH);
    }
}
