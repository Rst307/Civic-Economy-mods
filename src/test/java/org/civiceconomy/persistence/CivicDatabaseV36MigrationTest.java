package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV36MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v35ManifestSurvivesFullClaimSnapshotMigration() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v35.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("a36d76bb-0aee-4d0d-8567-d1f6d3302319"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        UUID cycleId = UUID.fromString("c2bd17f4-34e1-4161-9339-1f14594747ad");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.openTerritoryMaintenanceCycle(
                    cycleId, "migration", "cycle", 1_000L, 2_000L, 900L);
            database.registerTerritoryMaintenanceAssessmentBatch(
                    cycleId, "migration", "batch", 0, "0".repeat(64), 950L);
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE territory_force_load_enforcement");
            statement.execute("DROP TABLE territory_maintenance_assessment_claim");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_cooldown_ends_at_epoch_millis");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_eligibility");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_fee_minor_units");
            statement.execute("ALTER TABLE territory_maintenance_policy DROP COLUMN restoration_fee_minor_units");
            statement.execute("ALTER TABLE territory_maintenance_policy DROP COLUMN restoration_cooldown_millis");
            statement.execute("PRAGMA user_version = 35");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(50, migrated.schemaVersion());
            assertEquals(
                    cycleId,
                    migrated.territoryMaintenanceAssessmentBatch("migration", "batch")
                            .cycleId());
            assertEquals(List.of(), migrated.territoryMaintenanceAssessmentClaims(cycleId));
        }
    }
}
