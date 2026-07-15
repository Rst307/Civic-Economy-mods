package org.civiceconomy.mint;

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
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectiveTerritoryMintAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void derivesCurrentCycleAndUsesFloorDivForNegativeMintCoordinates() {
        NationId nationId = NationId.create();
        UUID teamId = UUID.randomUUID();
        try (CivicDatabase database = database()) {
            database.registerNation(
                    nationId.value(), "test", "nation", teamId, NOW.minusSeconds(2).toEpochMilli());
            TerritoryMaintenanceRegistry registry = new TerritoryMaintenanceRegistry(database, CLOCK);
            var cycle = registry.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("territory"), "cycle", NOW.minusSeconds(1), NOW.plusSeconds(60)));
            registry.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("territory"), "assessment", cycle.cycleId(), nationId,
                    teamId, "minecraft:overworld", -1, -2, 0L, "Effective Mint territory"));
            database.settleZeroCostTerritoryMaintenance(
                    UUID.randomUUID(), "territory", "settle", cycle.cycleId(), nationId.value(),
                    "Zero-cost settlement", NOW.toEpochMilli());
            EffectiveTerritoryMintAuthority authority = new EffectiveTerritoryMintAuthority(
                    database,
                    new EffectiveTerritoryQuery(
                            registry,
                            (dimension, chunkX, chunkZ) ->
                                    chunkX == -1 && chunkZ == -2
                                            ? Optional.of(teamId)
                                            : Optional.empty()),
                    CLOCK);

            assertTrue(authority.isEffective(mint(nationId, -1, -17)));
            assertFalse(authority.isEffective(mint(nationId, 0, -17)));
        }
    }

    private RegisteredMint mint(NationId nationId, int blockX, int blockZ) {
        return new RegisteredMint(
                UUID.randomUUID(), new ServiceIdentity("mint"), "register", nationId,
                "minecraft:overworld", blockX, 64, blockZ, UUID.randomUUID(),
                UUID.randomUUID(), true, UUID.randomUUID(), UUID.randomUUID(), "IDLE",
                "Mint", NOW);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("mint-territory.sqlite3"),
                new DatabaseIdentity(
                        UUID.randomUUID(), "0.1.0-probe", "1.21-2.3.0.5",
                        "2101.1.10", "2101.1.20"));
    }
}
