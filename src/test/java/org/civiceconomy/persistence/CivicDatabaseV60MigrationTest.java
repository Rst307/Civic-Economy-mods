package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.FiscalBillExpiryProcessor;
import org.civiceconomy.fiscal.FiscalBillKind;
import org.civiceconomy.fiscal.FiscalBillState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV60MigrationTest {
    private static final Instant NOW = Instant.parse("2026-07-16T05:00:00Z");
    private static final UUID BILL_ID =
            UUID.fromString("22222222-3333-4444-5555-666666666666");

    @TempDir
    Path temporaryDirectory;

    @Test
    void v59DatabaseAddsAuditedFiscalBillExpiry() throws Exception {
        Path file = temporaryDirectory.resolve("v59-fiscal-bill-expiry.sqlite3");
        DatabaseIdentity identity = identity();
        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            current.issueFiscalBill(
                    BILL_ID,
                    "revenue-office",
                    "migration-bill",
                    "player:migration-payer",
                    "nation:migration:treasury",
                    400L,
                    FiscalBillKind.FEE.name(),
                    "Migration Fiscal Bill",
                    NOW.minusSeconds(1L).toEpochMilli());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE fiscal_bill_expiry");
            statement.execute("PRAGMA user_version = 59");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            var expired = new FiscalBillExpiryProcessor(
                            migrated, Clock.fixed(NOW, ZoneOffset.UTC))
                    .expireDue();

            assertEquals(60, migrated.schemaVersion());
            assertEquals(1, expired.size());
            assertEquals(FiscalBillState.EXPIRED, expired.getFirst().state());
            assertEquals(
                    "automatic-expiry:" + BILL_ID,
                    migrated.fiscalBillExpiry(BILL_ID).requestId());
        }
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
