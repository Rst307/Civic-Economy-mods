package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV35MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v34MaintenancePolicySurvivesBatchManifestMigration() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v34.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("fa17c042-c51a-4d83-a15d-617861658e57"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        UUID cycleId = UUID.fromString("78da51aa-dadf-47ee-8cc3-e55024f189ee");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.scheduleTerritoryMaintenancePolicy(
                    UUID.randomUUID(),
                    "migration",
                    "maintenance-policy",
                    "console",
                    604_800_000L,
                    75L,
                    15_000,
                    25L,
                    6_000,
                    2_000L,
                    "Migration policy",
                    1_000L);
            database.openTerritoryMaintenanceCycle(
                    cycleId, "migration", "cycle", 2_000L, 3_000L, 1_500L);
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE territory_maintenance_assessment_claim");
            statement.execute("DROP TABLE territory_maintenance_assessment_batch");
            statement.execute("PRAGMA user_version = 34");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(37, migrated.schemaVersion());
            assertEquals(
                    75L,
                    migrated.territoryMaintenancePolicy("migration", "maintenance-policy")
                            .baseMaintenancePerChargeableClaimMinorUnits());
            assertNotNull(migrated.registerTerritoryMaintenanceAssessmentBatch(
                    cycleId,
                    "migration",
                    "batch",
                    0,
                    "0".repeat(64),
                    2_000L));
        }
    }
}
