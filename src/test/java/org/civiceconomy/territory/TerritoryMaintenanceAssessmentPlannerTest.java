package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void suspendedClaimAddsOnlyRestorationFeeAndNextCycleMaintenance() {
        var position = new TerritoryClaimPosition("minecraft:overworld", 0, 0);

        TerritoryMaintenanceClaimSnapshot assessment =
                new TerritoryMaintenanceAssessmentPlanner().plan(
                                NATION,
                                TEAM,
                                new Capital("minecraft:overworld", 0, 0),
                                List.of(new TerritoryMaintenanceObservedClaim(position, false)),
                                new TerritoryFreeAllocation(NATION, 0, 0, 0, 0),
                                policy(100L),
                                Instant.parse("2026-09-01T00:00:00Z"),
                                Map.of(position, new TerritoryMaintenanceRestorationHistory(
                                        true, Optional.empty())))
                        .getFirst();

        assertEquals(100L, assessment.maintenanceDueMinorUnits());
        assertEquals(30L, assessment.restorationFeeMinorUnits());
        assertEquals(130L, assessment.totalDueMinorUnits());
        assertEquals(
                TerritoryMaintenanceRestorationEligibility.ELIGIBLE,
                assessment.restorationEligibility());
        assertEquals(
                Optional.of(Instant.parse("2026-09-15T00:00:00Z")),
                assessment.restorationCooldownEndsAt());
    }

    @Test
    void restorationInsideCooldownRemainsIneligibleWithoutChargingHistoricalArrears() {
        var position = new TerritoryClaimPosition("minecraft:overworld", 0, 0);
        Instant eligibleAt = Instant.parse("2026-09-08T00:00:00Z");

        TerritoryMaintenanceClaimSnapshot assessment =
                new TerritoryMaintenanceAssessmentPlanner().plan(
                                NATION,
                                TEAM,
                                new Capital("minecraft:overworld", 0, 0),
                                List.of(new TerritoryMaintenanceObservedClaim(position, false)),
                                new TerritoryFreeAllocation(NATION, 0, 0, 0, 0),
                                policy(100L),
                                Instant.parse("2026-09-01T00:00:00Z"),
                                Map.of(position, new TerritoryMaintenanceRestorationHistory(
                                        true, Optional.of(eligibleAt))))
                        .getFirst();

        assertEquals(100L, assessment.maintenanceDueMinorUnits());
        assertEquals(0L, assessment.restorationFeeMinorUnits());
        assertEquals(
                TerritoryMaintenanceRestorationEligibility.COOLDOWN_BLOCKED,
                assessment.restorationEligibility());
        assertEquals(Optional.of(eligibleAt), assessment.restorationCooldownEndsAt());
        assertEquals(false, assessment.fundingEligible());
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
                Duration.ofHours(6),
                30L,
                Duration.ofDays(14),
                6_000,
                Instant.EPOCH,
                "console",
                "Test policy",
                Instant.EPOCH);
    }
}
