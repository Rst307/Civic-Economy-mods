package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV33MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v32SettlementConclusionsBecomeAuditableOutcomes() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v32.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("19ba91bf-22cb-42a2-b9ef-7045a7fce2db"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        UUID fundedNationId = UUID.fromString("afdd3674-bb32-4729-844a-f3d6932e7aa5");
        UUID suspendedNationId = UUID.fromString("a3d7fc70-4512-4289-80e4-e835c18a7237");
        UUID teamId = UUID.fromString("3396fc71-255e-47a9-947e-c231066f1aef");
        UUID fundedCycleId = UUID.fromString("cdcd9c34-e1e2-4e26-a8b3-607cdb72fe2b");
        UUID suspendedCycleId = UUID.fromString("e4da7826-6078-4774-bcc4-f55b855134b4");
        UUID fundedAssessmentId = UUID.fromString("527994f8-99f0-4745-950d-12f2f6cd1855");
        UUID suspendedAssessmentId = UUID.fromString("3287a22a-faae-4ccf-a81d-9ba31f96d3ae");
        UUID fundedSettlementId = UUID.fromString("0983cc0e-90c5-4325-a59f-a4a969dd6f27");
        UUID suspendedSettlementId = UUID.fromString("29ca6ddc-af6f-4900-a2d9-98dc9d164955");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(fundedNationId, "migration", "funded-nation", teamId, 1_000L);
            database.registerNation(
                    suspendedNationId,
                    "migration",
                    "suspended-nation",
                    UUID.fromString("92943905-e622-4fb3-adf1-796c19dd4d7b"),
                    1_001L);
            database.openTerritoryMaintenanceCycle(
                    fundedCycleId, "migration", "funded-cycle", 2_000L, 4_000L, 1_500L);
            database.openTerritoryMaintenanceCycle(
                    suspendedCycleId, "migration", "suspended-cycle", 5_000L, 7_000L, 4_500L);
            database.assessTerritoryFiscalValidity(
                    fundedAssessmentId, "migration", "funded-assessment", fundedCycleId,
                    fundedNationId, teamId, "minecraft:overworld", 1, 2, 100L,
                    "CAPITAL", "Funded migration assessment", 3_000L);
            database.assessTerritoryFiscalValidity(
                    suspendedAssessmentId, "migration", "suspended-assessment", suspendedCycleId,
                    suspendedNationId, UUID.fromString("92943905-e622-4fb3-adf1-796c19dd4d7b"),
                    "minecraft:overworld", 3, 4, 50L,
                    "ORDINARY", "Suspended migration assessment", 6_000L);
            String treasury = "nation:" + fundedNationId + ":treasury";
            StoredReservation reservation =
                    database.reserve("migration", "reserve", treasury, 100L, "Migration maintenance");
            StoredPaymentTransaction payment = database.preparePayment(
                    "migration",
                    "payment",
                    reservation.reservationId(),
                    "system:territory:public-maintenance-fund",
                    100L);
            database.markExternalApplied(payment.transactionId());
            database.commitPayment(payment.transactionId(), reservation.reservationId());
            database.confirmTerritoryMaintenanceSettlement(
                    fundedSettlementId,
                    "migration",
                    "funded-settlement",
                    fundedCycleId,
                    fundedNationId,
                    reservation.reservationId(),
                    payment.transactionId(),
                    null,
                    List.of(fundedAssessmentId),
                    List.of(),
                    "Funded legacy conclusion",
                    3_500L);
            database.suspendTerritoryMaintenance(
                    suspendedSettlementId,
                    "migration",
                    "suspended-settlement",
                    suspendedCycleId,
                    suspendedNationId,
                    "Suspended legacy conclusion",
                    6_500L);
        }
        downgradeSettlementTablesToV32(databaseFile);

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(78, migrated.schemaVersion());
            StoredTerritoryMaintenanceSettlement funded =
                    migrated.territoryMaintenanceSettlement("migration", "funded-settlement");
            assertEquals("FULLY_FUNDED", funded.outcome());
            assertEquals(List.of(fundedAssessmentId), funded.fundedAssessmentIds());
            assertEquals(List.of(), funded.suspendedAssessmentIds());
            StoredTerritoryMaintenanceSettlement suspended =
                    migrated.territoryMaintenanceSettlement("migration", "suspended-settlement");
            assertEquals("UNFUNDED", suspended.outcome());
            assertEquals(List.of(), suspended.fundedAssessmentIds());
            assertEquals(List.of(suspendedAssessmentId), suspended.suspendedAssessmentIds());
        }
    }

    private static void downgradeSettlementTablesToV32(Path databaseFile) throws Exception {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE territory_force_load_enforcement");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_cooldown_ends_at_epoch_millis");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_eligibility");
            statement.execute("ALTER TABLE territory_fiscal_assessment DROP COLUMN restoration_fee_minor_units");
            statement.execute("DROP TABLE territory_maintenance_policy");
            statement.execute("DROP TABLE territory_maintenance_assessment_claim");
            statement.execute("DROP TABLE territory_maintenance_assessment_batch");
            statement.execute("DROP TABLE territory_maintenance_settlement_assessment");
            statement.execute("ALTER TABLE territory_maintenance_settlement RENAME TO settlement_v33");
            statement.execute("""
                    CREATE TABLE territory_maintenance_settlement (
                        settlement_id TEXT PRIMARY KEY,
                        service_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        cycle_id TEXT NOT NULL REFERENCES territory_maintenance_cycle(cycle_id),
                        nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                        reservation_id TEXT UNIQUE REFERENCES fiscal_reservation(reservation_id),
                        public_fund_payment_id TEXT UNIQUE
                            REFERENCES payment_transaction(transaction_id),
                        destruction_operation_id TEXT UNIQUE
                            REFERENCES permanent_destruction_operation(operation_id),
                        validity TEXT NOT NULL CHECK (validity IN ('EFFECTIVE', 'SUSPENDED')),
                        reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                        settled_at_epoch_millis INTEGER NOT NULL
                            CHECK (settled_at_epoch_millis >= 0),
                        UNIQUE (service_identity, request_id),
                        UNIQUE (cycle_id, nation_id)
                    )
                    """);
            statement.execute("""
                    INSERT INTO territory_maintenance_settlement
                    SELECT settlement_id, service_identity, request_id, cycle_id, nation_id,
                           reservation_id, public_fund_payment_id, destruction_operation_id,
                           CASE outcome WHEN 'UNFUNDED' THEN 'SUSPENDED' ELSE 'EFFECTIVE' END,
                           reason, settled_at_epoch_millis
                    FROM settlement_v33
                    """);
            statement.execute("DROP TABLE settlement_v33");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 32");
        }
    }
}
