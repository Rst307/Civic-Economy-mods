package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectiveTerritoryQueryTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void requiresCurrentFtbOwnershipToMatchThePersistedAssessment() {
        NationId nationId = NationId.create();
        UUID teamId = UUID.randomUUID();
        try (CivicDatabase database = database()) {
            database.registerNation(
                    nationId.value(), "test", "nation", teamId, NOW.minusSeconds(1).toEpochMilli());
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(
                    database, Clock.fixed(NOW, ZoneOffset.UTC));
            var cycle = registry.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("civiceconomy-territory"),
                    "cycle",
                    NOW,
                    NOW.plusSeconds(3600L)));
            registry.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("civiceconomy-territory"),
                    "assessment",
                    cycle.cycleId(),
                    nationId,
                    teamId,
                    "minecraft:overworld",
                    3,
                    5,
                    0L,
                    TerritoryFiscalValidity.EFFECTIVE,
                    "Inside free allocation"));

            assertTrue(new EffectiveTerritoryQuery(
                            registry, (dimension, chunkX, chunkZ) -> Optional.of(teamId))
                    .isEffective(cycle.cycleId(), nationId, "minecraft:overworld", 3, 5));
            assertFalse(new EffectiveTerritoryQuery(
                            registry, (dimension, chunkX, chunkZ) -> Optional.empty())
                    .isEffective(cycle.cycleId(), nationId, "minecraft:overworld", 3, 5));
            assertFalse(new EffectiveTerritoryQuery(
                            registry,
                            (dimension, chunkX, chunkZ) -> Optional.of(UUID.randomUUID()))
                    .isEffective(cycle.cycleId(), nationId, "minecraft:overworld", 3, 5));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("effective-territory.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("7b9d6fef-f529-41bb-a60e-d5275453d113"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
