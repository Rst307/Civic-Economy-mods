package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryMaintenanceRegistryTest {
    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-01T00:00:00Z");
    private static final NationId NATION_ID = NationId.create();
    private static final UUID TEAM_ID = UUID.randomUUID();

    @TempDir Path temporaryDirectory;

    @Test
    void persistsCycleAndExactClaimAssessmentAcrossRestart() {
        UUID cycleId;
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            TerritoryMaintenanceCycle cycle = registry.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("civiceconomy-territory"),
                    "maintenance-2026-08",
                    START,
                    END));
            cycleId = cycle.cycleId();
            TerritoryFiscalAssessment assessment = registry.assess(
                    new AssessTerritoryFiscalValidity(
                            new ServiceIdentity("civiceconomy-territory"),
                            "assess-overworld-4-7",
                            cycleId,
                            NATION_ID,
                            TEAM_ID,
                            "minecraft:overworld",
                            4,
                            7,
                            250L,
                            TerritoryFiscalValidity.EFFECTIVE,
                            "Maintenance funded"));

            assertEquals(assessment, registry.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("civiceconomy-territory"),
                    "assess-overworld-4-7",
                    cycleId,
                    NATION_ID,
                    TEAM_ID,
                    "minecraft:overworld",
                    4,
                    7,
                    250L,
                    TerritoryFiscalValidity.EFFECTIVE,
                    "Maintenance funded")));
        }

        try (CivicDatabase database = database()) {
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            assertTrue(registry.isEffective(
                    cycleId, NATION_ID, TEAM_ID, "minecraft:overworld", 4, 7));
            assertFalse(registry.isEffective(
                    cycleId, NATION_ID, UUID.randomUUID(), "minecraft:overworld", 4, 7));
            assertFalse(registry.isEffective(
                    cycleId, NATION_ID, TEAM_ID, "minecraft:overworld", 5, 7));
        }
    }

    @Test
    void rejectsOverlappingCyclesAndChangedAssessmentReplay() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database);
            TerritoryMaintenanceCycle cycle = registry.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("civiceconomy-territory"), "cycle-a", START, END));
            assertThrows(IllegalStateException.class, () -> registry.openCycle(
                    new OpenTerritoryMaintenanceCycle(
                            new ServiceIdentity("civiceconomy-territory"),
                            "cycle-b",
                            START.plusSeconds(1),
                            END.plusSeconds(1))));
            registry.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("civiceconomy-territory"), "assessment", cycle.cycleId(),
                    NATION_ID, TEAM_ID, "minecraft:overworld", 1, 2, 100L,
                    TerritoryFiscalValidity.SUSPENDED, "Insufficient maintenance funds"));
            assertThrows(org.civiceconomy.fiscal.IdempotencyConflictException.class, () ->
                    registry.assess(new AssessTerritoryFiscalValidity(
                            new ServiceIdentity("civiceconomy-territory"), "assessment", cycle.cycleId(),
                            NATION_ID, TEAM_ID, "minecraft:overworld", 1, 2, 100L,
                            TerritoryFiscalValidity.EFFECTIVE, "Changed")));
        }
    }

    private void registerNation(CivicDatabase database) {
        database.registerNation(NATION_ID.value(), "test", "nation", TEAM_ID, START.minusSeconds(1).toEpochMilli());
    }

    private CivicDatabase database() {
        return CivicDatabase.open(temporaryDirectory.resolve("maintenance.sqlite3"),
                new DatabaseIdentity(UUID.fromString("f71a1a1f-70fc-4d4d-a359-e47b22f85ba9"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
