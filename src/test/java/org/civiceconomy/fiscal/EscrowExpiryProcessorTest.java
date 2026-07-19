package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EscrowExpiryProcessorTest {
    private static final Instant NOW = Instant.parse("2026-07-16T01:00:00Z");
    private static final AccountId TREASURY =
            new AccountId("nation:automatic-expiry:treasury");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("public-works");

    @TempDir
    Path temporaryDirectory;

    @Test
    void expiresOnlyDueActiveEscrowsAndReplayDoesNotReleaseTwice() {
        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, NOW);
            Escrow due = ledger.openEscrow(new OpenEscrow(
                    SERVICE,
                    "due-escrow",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(300L),
                    "contract:due",
                    "Due public works escrow",
                    NOW.plusSeconds(60L)));
            Escrow future = ledger.openEscrow(new OpenEscrow(
                    SERVICE,
                    "future-escrow",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(200L),
                    "contract:future",
                    "Future public works escrow",
                    NOW.plusSeconds(120L)));
            EscrowExpiryProcessor processor = new EscrowExpiryProcessor(
                    database,
                    Clock.fixed(NOW.plusSeconds(90L), ZoneOffset.UTC));

            assertEquals(1, processor.expireDue().size());
            assertEquals(EscrowState.EXPIRED, ledger.escrow(due.escrowId()).state());
            assertEquals(EscrowState.RESERVED, ledger.escrow(future.escrowId()).state());
            assertEquals(MoneyAmount.ofMinorUnits(200L), ledger.reservedBalance(TREASURY));

            assertEquals(0, processor.expireDue().size());
            assertEquals(MoneyAmount.ofMinorUnits(200L), ledger.reservedBalance(TREASURY));
        }
    }

    @Test
    void pendingPaymentBlocksOnlyItsOwnEscrowAndOtherDueEscrowsStillExpire() {
        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, NOW);
            Escrow blocked = ledger.openEscrow(new OpenEscrow(
                    SERVICE,
                    "blocked-escrow",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(300L),
                    "contract:blocked",
                    "Ambiguous payment escrow",
                    NOW.plusSeconds(60L)));
            Escrow safe = ledger.openEscrow(new OpenEscrow(
                    SERVICE,
                    "safe-escrow",
                    TREASURY,
                    MoneyAmount.ofMinorUnits(200L),
                    "contract:safe",
                    "Independent due escrow",
                    NOW.plusSeconds(61L)));
            PaymentCoordinator payments = new PaymentCoordinator(
                    database,
                    ignored -> {
                        throw new IllegalStateException("Synthetic external payment failure");
                    });
            assertThrows(
                    IllegalStateException.class,
                    () -> payments.settle(
                            new SettleReservation(
                                    SERVICE,
                                    "blocked-payment",
                                    blocked.reservationId(),
                                    new AccountId("organization:builder:fiscal"),
                                    MoneyAmount.ofMinorUnits(100L)),
                            FailurePoint.NONE));
            EscrowExpiryProcessor processor = new EscrowExpiryProcessor(
                    database,
                    Clock.fixed(NOW.plusSeconds(90L), ZoneOffset.UTC));

            assertEquals(1, processor.expireDue().size());
            assertEquals(EscrowState.RESERVED, ledger.escrow(blocked.escrowId()).state());
            assertEquals(EscrowState.EXPIRED, ledger.escrow(safe.escrowId()).state());
            assertEquals(MoneyAmount.ofMinorUnits(300L), ledger.reservedBalance(TREASURY));
        }
    }

    private FiscalLedger ledger(CivicDatabase database, Instant now) {
        return new FiscalLedger(
                database,
                ignored -> MoneyAmount.ofMinorUnits(1_000L),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("automatic-escrow-expiry.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("6a8b46d8-3d2f-48c5-b61b-a275f6f9d504"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
