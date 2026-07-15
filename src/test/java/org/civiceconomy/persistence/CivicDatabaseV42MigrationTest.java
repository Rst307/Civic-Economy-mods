package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.territory.AssessTerritoryFiscalValidity;
import org.civiceconomy.territory.OpenTerritoryMaintenanceCycle;
import org.civiceconomy.territory.SuspendTerritoryMaintenance;
import org.civiceconomy.territory.TerritoryForceLoadEnforcementRegistry;
import org.civiceconomy.territory.TerritoryMaintenancePriority;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV42MigrationTest {
    private static final ServiceIdentity SERVICE = new ServiceIdentity("migration-v42");

    @TempDir Path temporaryDirectory;

    @Test
    void v41EnforcementReceivesExactCycleBasedForceLoadGrace() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v41.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("476dadc4-e8c2-4762-865d-502c1083d653"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        UUID enforcementId;
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            NationId nationId = new NationId(
                    UUID.fromString("f0abe4e8-6a99-4280-9306-cf56794026dc"));
            UUID teamId = UUID.fromString("486b0fef-7eb2-4c56-a419-bf0388ca74f9");
            database.registerNation(nationId.value(), "migration", "nation", teamId, 1_000L);
            TerritoryMaintenanceRegistry maintenance =
                    new TerritoryMaintenanceRegistry(database);
            var cycle = maintenance.openCycle(new OpenTerritoryMaintenanceCycle(
                    SERVICE,
                    "cycle",
                    Instant.ofEpochMilli(2_000L),
                    Instant.ofEpochMilli(3_000L)));
            var assessment = maintenance.assess(new AssessTerritoryFiscalValidity(
                    SERVICE,
                    "assessment",
                    cycle.cycleId(),
                    nationId,
                    teamId,
                    "minecraft:overworld",
                    11,
                    12,
                    10L,
                    TerritoryMaintenancePriority.ORDINARY,
                    "Migration fixture"));
            maintenance.suspend(new SuspendTerritoryMaintenance(
                    SERVICE,
                    "settlement",
                    cycle.cycleId(),
                    nationId,
                    "Unfunded migration fixture"));
            enforcementId = new TerritoryForceLoadEnforcementRegistry(
                            database,
                            Clock.fixed(Instant.ofEpochMilli(4_000L), ZoneOffset.UTC))
                    .prepare(
                            SERVICE,
                            "force-load",
                            assessment.assessmentId(),
                            "Migration enforcement")
                    .enforcementId();
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE territory_force_load_enforcement
                    DROP COLUMN not_before_epoch_millis
                    """);
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 41");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(53, migrated.schemaVersion());
            TerritoryForceLoadEnforcementRegistry registry =
                    new TerritoryForceLoadEnforcementRegistry(
                            migrated,
                            Clock.fixed(
                                    Instant.ofEpochMilli(86_403_000L),
                                    ZoneOffset.UTC));
            assertEquals(
                    Instant.ofEpochMilli(86_403_000L),
                    registry.find(enforcementId).orElseThrow().notBefore());
            assertEquals(1, registry.incomplete().size());
        }
    }
}
