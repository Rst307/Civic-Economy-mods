package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV32MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v31AssessmentReceivesConservativeOrdinaryPriority() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v31.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("7aa66f41-1ca7-4e98-aa27-62acd50d9886"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        UUID nationId = UUID.fromString("399046de-bf13-41cc-a231-475076ff9190");
        UUID teamId = UUID.fromString("d0d81ddc-753b-4556-b830-cee10c923b90");
        UUID cycleId = UUID.fromString("bcae269c-cec7-4a94-a32c-c71736aa4a11");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(nationId, "migration", "nation", teamId, 1_000L);
            database.openTerritoryMaintenanceCycle(
                    cycleId, "migration", "cycle", 2_000L, 4_000L, 1_500L);
            database.assessTerritoryFiscalValidity(
                    UUID.randomUUID(), "migration", "assessment", cycleId,
                    nationId, teamId, "minecraft:overworld", 1, 2, 100L,
                    "CAPITAL", "Migration assessment", 3_000L);
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN priority");
            statement.execute("PRAGMA user_version = 31");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(32, migrated.schemaVersion());
            assertEquals(
                    "ORDINARY",
                    migrated.territoryFiscalAssessment("migration", "assessment").priority());
        }
    }
}
