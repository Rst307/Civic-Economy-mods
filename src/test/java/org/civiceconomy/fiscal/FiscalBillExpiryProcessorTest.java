package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalBillExpiryProcessorTest {
    private static final Instant NOW = Instant.parse("2026-07-16T04:00:00Z");
    private static final AccountId PAYER = new AccountId("player:bill-expiry-payer");
    private static final AccountId TREASURY =
            new AccountId("nation:bill-expiry:treasury");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("revenue-office");

    @TempDir
    Path temporaryDirectory;

    @Test
    void classifiesOnlyOverdueUnfundedBillsWithoutCreatingFiscalHoldsAndReplaysExactly() {
        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, NOW);
            FiscalBill due = ledger.issueBill(issue("due-bill", NOW.plusSeconds(60L)));
            FiscalBill future = ledger.issueBill(issue("future-bill", NOW.plusSeconds(120L)));
            FiscalBillExpiryProcessor processor = new FiscalBillExpiryProcessor(
                    database,
                    Clock.fixed(NOW.plusSeconds(90L), ZoneOffset.UTC));

            assertEquals(1, processor.expireDue().size());
            assertEquals(FiscalBillState.EXPIRED, ledger.issueBill(request(due)).state());
            assertEquals(FiscalBillState.ISSUED, ledger.issueBill(request(future)).state());
            assertEquals(Optional.empty(), ledger.issueBill(request(due)).escrowId());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(PAYER));
            assertThrows(
                    IllegalStateException.class,
                    () -> ledger.fundBill(new FundFiscalBill(
                            new ServiceIdentity("payer-service"),
                            "fund-expired-bill",
                            due.billId())));
            assertEquals(
                    "automatic-expiry:" + due.billId(),
                    database.fiscalBillExpiry(due.billId()).requestId());

            assertEquals(0, processor.expireDue().size());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(PAYER));
        }
    }

    @Test
    void changedBillDoesNotBlockLaterOverdueBills() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("changed-fiscal-bill.sqlite3");
        UUID firstId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID changedId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID laterId = UUID.fromString("10000000-0000-0000-0000-000000000003");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity())) {
            createOverdueBill(database, firstId, "first-bill", 3L);
            createOverdueBill(database, changedId, "changed-bill", 2L);
            createOverdueBill(database, laterId, "later-bill", 1L);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                    var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER change_next_bill_after_first_expiry
                        AFTER INSERT ON fiscal_bill_expiry
                        WHEN NEW.bill_id = '10000000-0000-0000-0000-000000000001'
                        BEGIN
                            UPDATE fiscal_bill
                            SET state = 'EXPIRED'
                            WHERE bill_id = '10000000-0000-0000-0000-000000000002';
                        END
                        """);
            }
            FiscalBillExpiryProcessor processor = new FiscalBillExpiryProcessor(
                    database, Clock.fixed(NOW, ZoneOffset.UTC));

            assertEquals(2, processor.expireDue().size());
            assertEquals("EXPIRED", database.fiscalBill(firstId).state());
            assertEquals("EXPIRED", database.fiscalBill(changedId).state());
            assertEquals("EXPIRED", database.fiscalBill(laterId).state());
            assertNull(database.fiscalBillExpiry(changedId));
            assertEquals(
                    "automatic-expiry:" + laterId,
                    database.fiscalBillExpiry(laterId).requestId());
        }
    }

    private static void createOverdueBill(
            CivicDatabase database, UUID billId, String requestId, long dueOffsetMillis) {
        database.issueFiscalBill(
                billId,
                SERVICE.value(),
                requestId,
                PAYER.value(),
                TREASURY.value(),
                100L,
                FiscalBillKind.FEE.name(),
                "Bill expiry isolation",
                NOW.minusMillis(dueOffsetMillis).toEpochMilli());
    }

    private static IssueFiscalBill issue(String requestId, Instant dueAt) {
        return new IssueFiscalBill(
                SERVICE,
                requestId,
                PAYER,
                TREASURY,
                MoneyAmount.ofMinorUnits(300L),
                FiscalBillKind.FEE,
                "Automatic Bill expiry",
                dueAt);
    }

    private static IssueFiscalBill request(FiscalBill bill) {
        return new IssueFiscalBill(
                bill.serviceIdentity(),
                bill.requestId(),
                bill.payerAccount(),
                bill.beneficiaryAccount(),
                bill.amount(),
                bill.kind(),
                bill.purpose(),
                bill.dueAt());
    }

    private static FiscalLedger ledger(CivicDatabase database, Instant now) {
        return new FiscalLedger(
                database,
                ignored -> MoneyAmount.ofMinorUnits(1_000L),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("fiscal-bill-expiry.sqlite3"), identity());
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("c7eaf052-0fa8-4b96-80ba-5a6b6d0ff6ad"),
                "0.1.0",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
