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
            statement.execute("DROP TABLE territory_force_load_enforcement");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN priority");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_cooldown_ends_at_epoch_millis");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_eligibility");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_fee_minor_units");
            statement.execute("DROP TABLE territory_maintenance_policy");
            statement.execute("DROP TABLE territory_maintenance_assessment_claim");
            statement.execute("DROP TABLE territory_maintenance_assessment_batch");
            statement.execute("DROP TABLE territory_maintenance_settlement_assessment");
            statement.execute("DROP TABLE territory_maintenance_settlement");
            statement.execute("""
                    CREATE TABLE territory_maintenance_settlement (
                        settlement_id TEXT PRIMARY KEY,
                        service_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        cycle_id TEXT NOT NULL
                            REFERENCES territory_maintenance_cycle(cycle_id),
                        nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                        reservation_id TEXT UNIQUE
                            REFERENCES fiscal_reservation(reservation_id),
                        public_fund_payment_id TEXT UNIQUE
                            REFERENCES payment_transaction(transaction_id),
                        destruction_operation_id TEXT UNIQUE
                            REFERENCES permanent_destruction_operation(operation_id),
                        validity TEXT NOT NULL
                            CHECK (validity IN ('EFFECTIVE', 'SUSPENDED')),
                        reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                        settled_at_epoch_millis INTEGER NOT NULL
                            CHECK (settled_at_epoch_millis >= 0),
                        UNIQUE (service_identity, request_id),
                        UNIQUE (cycle_id, nation_id)
                    )
                    """);
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 31");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(57, migrated.schemaVersion());
            assertEquals(
                    "ORDINARY",
                    migrated.territoryFiscalAssessment("migration", "assessment").priority());
        }
    }
}
