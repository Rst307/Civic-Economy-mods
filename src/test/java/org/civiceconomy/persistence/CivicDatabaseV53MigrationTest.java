package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV53MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v52DatabasePreservesSupplyEventsAndAddsTheSeparateCorrectionLedger()
            throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v52.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("08c4970a-1a92-4c24-a513-34c1510ca229"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID eventId = UUID.fromString("96f41820-f6cd-481b-828b-c4c5611a3852");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.confirmMonetarySupplyChange(
                    eventId,
                    "issuance-controller",
                    "seed-400",
                    "ISSUANCE",
                    400L,
                    "seed:400",
                    "Existing issuance",
                    1_000L,
                    1_000L);
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute(
                    "ALTER TABLE monetary_supply_event RENAME TO monetary_supply_event_v53");
            statement.execute("""
                    CREATE TABLE monetary_supply_event (
                        event_id TEXT PRIMARY KEY,
                        service_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        change_kind TEXT NOT NULL
                            CHECK (change_kind IN ('ISSUANCE', 'PERMANENT_DESTRUCTION')),
                        amount_minor_units INTEGER NOT NULL CHECK (amount_minor_units > 0),
                        external_reference TEXT NOT NULL
                            CHECK (length(trim(external_reference)) > 0),
                        reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                        confirmed_at_epoch_millis INTEGER NOT NULL
                            CHECK (confirmed_at_epoch_millis >= 0),
                        UNIQUE (service_identity, request_id),
                        UNIQUE (change_kind, external_reference)
                    )
                    """);
            statement.execute("""
                    INSERT INTO monetary_supply_event
                    SELECT * FROM monetary_supply_event_v53
                    """);
            statement.execute("DROP TABLE monetary_supply_event_v53");
            statement.execute("PRAGMA user_version = 52");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(58, migrated.schemaVersion());
            assertEquals(400L, migrated.cumulativeNetIssuanceMinorUnits());
            assertEquals(eventId,
                    migrated.monetarySupplyEvent("issuance-controller", "seed-400").eventId());
            assertNull(migrated.monetaryStockCorrection("civic-admin-console:Server", "missing"));
            assertEquals(
                    "PERMANENT_DESTRUCTION",
                    migrated.confirmMonetarySupplyChange(
                                    UUID.randomUUID(),
                                    "destruction-controller",
                                    "destroy-100",
                                    "PERMANENT_DESTRUCTION",
                                    100L,
                                    "destroy:100",
                                    "Confirmed destruction",
                                    2_000L,
                                    1_000L)
                            .changeKind());
            assertEquals(300L, migrated.cumulativeNetIssuanceMinorUnits());
        }
    }
}
