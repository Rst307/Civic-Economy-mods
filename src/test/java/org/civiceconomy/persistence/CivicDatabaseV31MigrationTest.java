package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV31MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v30AssessmentConclusionSurvivesWhileNewAssessmentsStartPending() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v30.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("3fdc9ab1-2745-4ca9-b77e-a9f2960c286f"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        UUID nationId = UUID.fromString("99495590-9417-4ec2-a5e6-8b739b234d99");
        UUID teamId = UUID.fromString("8c48e823-f89a-4df6-a7ae-6739b19076f8");
        UUID cycleId = UUID.fromString("d8140b02-f329-4c58-9e77-2dff70fa76b2");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(nationId, "migration", "nation", teamId, 1_000L);
            database.openTerritoryMaintenanceCycle(
                    cycleId, "migration", "cycle", 2_000L, 4_000L, 1_500L);
            database.assessTerritoryFiscalValidity(
                    UUID.randomUUID(), "migration", "legacy-assessment", cycleId,
                    nationId, teamId, "minecraft:overworld", 1, 2, 100L,
                    "ORDINARY",
                    "Legacy conclusion", 3_000L);
        }
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE territory_force_load_enforcement");
            statement.execute("UPDATE territory_fiscal_assessment SET validity = 'EFFECTIVE'");
            statement.execute("DROP TABLE territory_maintenance_policy");
            statement.execute("DROP TABLE territory_maintenance_assessment_claim");
            statement.execute("DROP TABLE territory_maintenance_assessment_batch");
            statement.execute("DROP TABLE territory_maintenance_settlement_assessment");
            statement.execute("DROP TABLE territory_maintenance_settlement");
            statement.execute("PRAGMA user_version = 30");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(47, migrated.schemaVersion());
            assertEquals(
                    "EFFECTIVE",
                    migrated.territoryFiscalAssessment("migration", "legacy-assessment").validity());
            assertEquals(
                    "PENDING",
                    migrated.assessTerritoryFiscalValidity(
                                    UUID.randomUUID(), "migration", "new-assessment", cycleId,
                                    nationId, teamId, "minecraft:overworld", 2, 2, 50L,
                                    "ORDINARY",
                                    "New assessment", 3_500L)
                            .validity());
        }
    }
}
