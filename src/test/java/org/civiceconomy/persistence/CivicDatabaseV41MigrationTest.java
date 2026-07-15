package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.territory.AssessTerritoryFiscalValidity;
import org.civiceconomy.territory.OpenTerritoryMaintenanceCycle;
import org.civiceconomy.territory.SuspendTerritoryMaintenance;
import org.civiceconomy.territory.TerritoryForceLoadEnforcementRegistry;
import org.civiceconomy.territory.TerritoryForceLoadEnforcementState;
import org.civiceconomy.territory.TerritoryMaintenancePriority;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV41MigrationTest {
    private static final ServiceIdentity SERVICE = new ServiceIdentity("migration-v41");

    @TempDir Path temporaryDirectory;

    @Test
    void v40DatabaseAddsDurableForceLoadEnforcementWithoutChangingAssessments()
            throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v40.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("a895e095-4a49-4dd4-8d8a-b514d79f56aa"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        UUID teamId = UUID.fromString("2545a789-ddfe-4c77-bdb0-3a3547932fef");
        UUID assessmentId;
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            NationId nationId = new NationId(
                    UUID.fromString("04d40a3b-f90d-4f0b-a1f9-5524223cfb79"));
            database.registerNation(nationId.value(), "migration", "nation", teamId, 1_000L);
            TerritoryMaintenanceRegistry maintenance =
                    new TerritoryMaintenanceRegistry(database);
            var cycle = maintenance.openCycle(new OpenTerritoryMaintenanceCycle(
                    SERVICE,
                    "cycle",
                    Instant.ofEpochMilli(2_000L),
                    Instant.ofEpochMilli(3_000L)));
            assessmentId = maintenance.assess(new AssessTerritoryFiscalValidity(
                            SERVICE,
                            "assessment",
                            cycle.cycleId(),
                            nationId,
                            teamId,
                            "minecraft:overworld",
                            7,
                            8,
                            10L,
                            TerritoryMaintenancePriority.ORDINARY,
                            "Migration fixture"))
                    .assessmentId();
            maintenance.suspend(new SuspendTerritoryMaintenance(
                    SERVICE,
                    "settlement",
                    cycle.cycleId(),
                    nationId,
                    "Unfunded migration fixture"));
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE territory_force_load_enforcement");
            statement.execute("PRAGMA user_version = 40");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(44, migrated.schemaVersion());
            var enforcement = new TerritoryForceLoadEnforcementRegistry(migrated)
                    .prepare(SERVICE, "force-load", assessmentId, "Migration enforcement");
            assertEquals(TerritoryForceLoadEnforcementState.PREPARED, enforcement.state());
            assertEquals(teamId, enforcement.ftbTeamId());
            assertEquals(7, enforcement.position().chunkX());
            assertEquals(8, enforcement.position().chunkZ());
        }
    }
}
