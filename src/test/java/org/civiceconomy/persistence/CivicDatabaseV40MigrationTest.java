package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.territory.AssessTerritoryMaintenanceCycle;
import org.civiceconomy.territory.TerritoryMaintenanceAssessmentProcessor;
import org.civiceconomy.territory.TerritoryMaintenanceClaimSnapshot;
import org.civiceconomy.territory.TerritoryMaintenancePriority;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.civiceconomy.territory.TerritoryMaintenanceRestorationEligibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV40MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v39SnapshotMigratesToNonRestorationWithoutBreakingRecovery() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v39.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("9c34b5a6-3b1e-4566-b640-a03e4aab7cd9"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        ServiceIdentity service = new ServiceIdentity("migration-v40");
        NationId nationId = new NationId(
                UUID.fromString("943bc5a8-9629-4308-8f60-df4323ce5c4c"));
        UUID teamId = UUID.fromString("0128af8a-703d-49cc-b18e-546d12148fd0");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(nationId.value(), "migration", "nation", teamId, 1_000L);
            new TerritoryMaintenanceAssessmentProcessor(new TerritoryMaintenanceRegistry(database))
                    .assess(new AssessTerritoryMaintenanceCycle(
                            service,
                            "migration-v40-cycle",
                            Instant.ofEpochMilli(2_000L),
                            Instant.ofEpochMilli(3_000L),
                            List.of(new TerritoryMaintenanceClaimSnapshot(
                                    nationId,
                                    teamId,
                                    "minecraft:overworld",
                                    1,
                                    2,
                                    75L,
                                    TerritoryMaintenancePriority.CAPITAL)),
                            "Migration assessment"));
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE territory_force_load_enforcement");
            dropRestorationColumns(statement, "territory_maintenance_assessment_claim");
            dropRestorationColumns(statement, "territory_fiscal_assessment");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 39");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(74, migrated.schemaVersion());
            var recovered = new TerritoryMaintenanceAssessmentProcessor(
                            new TerritoryMaintenanceRegistry(migrated))
                    .recover(service, "migration-v40-cycle", "Migration assessment")
                    .orElseThrow()
                    .assessments()
                    .getFirst();
            assertEquals(75L, recovered.maintenanceDue().minorUnits());
            assertEquals(0L, recovered.restorationFee().minorUnits());
            assertEquals(
                    TerritoryMaintenanceRestorationEligibility.NOT_REQUIRED,
                    recovered.restorationEligibility());
            assertEquals(Optional.empty(), recovered.restorationCooldownEndsAt());
        }
    }

    private static void dropRestorationColumns(
            java.sql.Statement statement, String table) throws Exception {
        statement.execute("ALTER TABLE " + table
                + " DROP COLUMN restoration_cooldown_ends_at_epoch_millis");
        statement.execute("ALTER TABLE " + table
                + " DROP COLUMN restoration_eligibility");
        statement.execute("ALTER TABLE " + table
                + " DROP COLUMN restoration_fee_minor_units");
    }
}
