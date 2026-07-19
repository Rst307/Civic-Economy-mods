package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.territory.AssessTerritoryMaintenanceCycle;
import org.civiceconomy.territory.TerritoryMaintenanceAssessmentProcessor;
import org.civiceconomy.territory.TerritoryMaintenanceClaimSnapshot;
import org.civiceconomy.territory.TerritoryMaintenancePriority;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV37MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v36CompleteAssessmentsBackfillMissingFullSnapshots() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v36.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("f915455f-c90e-4588-a537-550ae0884028"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        ServiceIdentity service = new ServiceIdentity("migration");
        NationId nationId = new NationId(
                UUID.fromString("99bf69ab-acf3-43f0-9760-84ca8b61c2f6"));
        UUID teamId = UUID.fromString("c91dd9b2-5586-4de6-947a-b1a1cebed876");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(nationId.value(), "migration", "nation", teamId, 1_000L);
            new TerritoryMaintenanceAssessmentProcessor(new TerritoryMaintenanceRegistry(database))
                    .assess(new AssessTerritoryMaintenanceCycle(
                            service,
                            "migration-cycle",
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
            statement.execute("DELETE FROM territory_maintenance_assessment_claim");
            statement.execute("ALTER TABLE territory_maintenance_assessment_claim DROP COLUMN restoration_cooldown_ends_at_epoch_millis");
            statement.execute("ALTER TABLE territory_maintenance_assessment_claim DROP COLUMN restoration_eligibility");
            statement.execute("ALTER TABLE territory_maintenance_assessment_claim DROP COLUMN restoration_fee_minor_units");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_cooldown_ends_at_epoch_millis");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_eligibility");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_fee_minor_units");
            statement.execute("ALTER TABLE territory_maintenance_policy DROP COLUMN restoration_fee_minor_units");
            statement.execute("ALTER TABLE territory_maintenance_policy DROP COLUMN restoration_cooldown_millis");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 36");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(86, migrated.schemaVersion());
            var recovered = new TerritoryMaintenanceAssessmentProcessor(
                            new TerritoryMaintenanceRegistry(migrated))
                    .recover(service, "migration-cycle", "Migration assessment")
                    .orElseThrow();
            assertEquals(1, recovered.assessments().size());
            assertEquals(75L, recovered.assessments().getFirst().maintenanceDue().minorUnits());
            assertEquals(
                    TerritoryMaintenancePriority.CAPITAL,
                    recovered.assessments().getFirst().priority());
        }
    }

    @Test
    void v36BackfillPreservesJavaUuidOrderAcrossSignedBoundary() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v36-uuid-order.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("02fd9cc9-aab2-456c-808d-cb4ea7e858e6"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        ServiceIdentity service = new ServiceIdentity("migration-order");
        NationId negativeMostSignificantBits = new NationId(
                UUID.fromString("f0000000-0000-0000-0000-000000000001"));
        NationId positiveMostSignificantBits = new NationId(
                UUID.fromString("00000000-0000-0000-0000-000000000002"));
        UUID negativeTeam = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID positiveTeam = UUID.fromString("40000000-0000-0000-0000-000000000002");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(
                    negativeMostSignificantBits.value(),
                    "migration-order",
                    "negative",
                    negativeTeam,
                    1_000L);
            database.registerNation(
                    positiveMostSignificantBits.value(),
                    "migration-order",
                    "positive",
                    positiveTeam,
                    1_001L);
            new TerritoryMaintenanceAssessmentProcessor(new TerritoryMaintenanceRegistry(database))
                    .assess(new AssessTerritoryMaintenanceCycle(
                            service,
                            "migration-uuid-order",
                            Instant.ofEpochMilli(2_000L),
                            Instant.ofEpochMilli(3_000L),
                            List.of(
                                    claim(positiveMostSignificantBits, positiveTeam, 2, 50L),
                                    claim(negativeMostSignificantBits, negativeTeam, 1, 75L)),
                            "Migration UUID order assessment"));
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE territory_force_load_enforcement");
            statement.execute("DELETE FROM territory_maintenance_assessment_claim");
            statement.execute("ALTER TABLE territory_maintenance_assessment_claim DROP COLUMN restoration_cooldown_ends_at_epoch_millis");
            statement.execute("ALTER TABLE territory_maintenance_assessment_claim DROP COLUMN restoration_eligibility");
            statement.execute("ALTER TABLE territory_maintenance_assessment_claim DROP COLUMN restoration_fee_minor_units");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_cooldown_ends_at_epoch_millis");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_eligibility");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_fee_minor_units");
            statement.execute("ALTER TABLE territory_maintenance_policy DROP COLUMN restoration_fee_minor_units");
            statement.execute("ALTER TABLE territory_maintenance_policy DROP COLUMN restoration_cooldown_millis");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 36");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            var recovered = new TerritoryMaintenanceAssessmentProcessor(
                            new TerritoryMaintenanceRegistry(migrated))
                    .recover(service, "migration-uuid-order", "Migration UUID order assessment")
                    .orElseThrow();
            assertEquals(
                    List.of(negativeMostSignificantBits, positiveMostSignificantBits),
                    recovered.assessments().stream()
                            .map(assessment -> assessment.nationId())
                            .toList());
        }
    }

    private static TerritoryMaintenanceClaimSnapshot claim(
            NationId nationId, UUID teamId, int chunkX, long due) {
        return new TerritoryMaintenanceClaimSnapshot(
                nationId,
                teamId,
                "minecraft:overworld",
                chunkX,
                2,
                due,
                TerritoryMaintenancePriority.CAPITAL);
    }
}
