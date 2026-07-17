package org.civiceconomy.production;

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
import org.civiceconomy.territory.AssessTerritoryFiscalValidity;
import org.civiceconomy.territory.EffectiveTerritoryQuery;
import org.civiceconomy.territory.OpenTerritoryMaintenanceCycle;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectiveTerritoryFacilityAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void requiresCurrentOwnershipAndFiscalValidityForTheExactBoundTeam() {
        NationId nationId = NationId.create();
        UUID teamId = UUID.randomUUID();
        TerritoryClaimPosition claim =
                new TerritoryClaimPosition("minecraft:overworld", 4, -3);
        try (CivicDatabase database = database()) {
            database.registerNation(
                    nationId.value(), "test", "nation", teamId,
                    NOW.minusSeconds(2L).toEpochMilli());
            TerritoryMaintenanceRegistry maintenance =
                    new TerritoryMaintenanceRegistry(database, CLOCK);
            var cycle = maintenance.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("territory"),
                    "cycle",
                    NOW.minusSeconds(1L),
                    NOW.plusSeconds(60L)));
            maintenance.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("territory"),
                    "assessment",
                    cycle.cycleId(),
                    nationId,
                    teamId,
                    claim.dimensionId(),
                    claim.chunkX(),
                    claim.chunkZ(),
                    0L,
                    "Effective Facility territory"));
            database.settleZeroCostTerritoryMaintenance(
                    UUID.randomUUID(),
                    "territory",
                    "settle",
                    cycle.cycleId(),
                    nationId.value(),
                    "Zero-cost settlement",
                    NOW.toEpochMilli());
            EffectiveTerritoryFacilityAuthority authority =
                    new EffectiveTerritoryFacilityAuthority(
                            database,
                            new EffectiveTerritoryQuery(
                                    maintenance,
                                    (dimension, chunkX, chunkZ) ->
                                            dimension.equals(claim.dimensionId())
                                                            && chunkX == claim.chunkX()
                                                            && chunkZ == claim.chunkZ()
                                                    ? Optional.of(teamId)
                                                    : Optional.empty()),
                            CLOCK);

            assertTrue(authority.isEffective(nationId, teamId, claim));
            assertFalse(authority.isEffective(
                    nationId,
                    UUID.fromString("99999999-9999-9999-9999-999999999999"),
                    claim));
            assertFalse(authority.isEffective(
                    nationId,
                    teamId,
                    new TerritoryClaimPosition("minecraft:overworld", 5, -3)));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("facility-territory.sqlite3"),
                new DatabaseIdentity(
                        UUID.randomUUID(),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
