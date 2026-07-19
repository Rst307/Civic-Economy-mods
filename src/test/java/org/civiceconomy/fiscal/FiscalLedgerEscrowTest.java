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

class FiscalLedgerEscrowTest {
    private static final Instant NOW = Instant.parse("2026-07-14T03:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void reservedEscrowSurvivesRestartAndReplaysWithoutASecondHold() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        OpenEscrow request = new OpenEscrow(
                new ServiceIdentity("public-works"),
                "bridge-escrow",
                treasury,
                MoneyAmount.ofMinorUnits(400),
                "contract:bridge-17",
                "Bridge milestone escrow",
                NOW.plusSeconds(86_400));
        Escrow opened;

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, treasury);

            opened = ledger.openEscrow(request);

            assertEquals(EscrowState.RESERVED, opened.state());
            assertEquals(treasury, opened.sourceAccount());
            assertEquals(MoneyAmount.ofMinorUnits(400), opened.amount());
            assertEquals(MoneyAmount.ZERO, opened.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(400), opened.remainingAmount());
            assertEquals("contract:bridge-17", opened.externalObjectId());
            assertEquals(NOW.plusSeconds(86_400), opened.expiresAt());
            assertEquals(MoneyAmount.ofMinorUnits(600), ledger.availableBalance(treasury));
        }

        try (CivicDatabase reopened = database()) {
            FiscalLedger ledger = ledger(reopened, treasury);

            assertEquals(opened, ledger.openEscrow(request));
            assertEquals(MoneyAmount.ofMinorUnits(400), ledger.reservedBalance(treasury));
            assertEquals(MoneyAmount.ofMinorUnits(600), ledger.availableBalance(treasury));
        }
    }

    @Test
    void partialAndFinalSettlementAdvanceTheEscrowLifecycle() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        OpenEscrow request = new OpenEscrow(
                new ServiceIdentity("public-works"),
                "road-escrow",
                treasury,
                MoneyAmount.ofMinorUnits(400),
                "contract:road-9",
                "Road milestone escrow",
                NOW.plusSeconds(86_400));

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, treasury);
            Escrow escrow = ledger.openEscrow(request);
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});

            coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("public-works"),
                            "road-first-milestone",
                            escrow.reservationId(),
                            new AccountId("organization:road-builder:fiscal"),
                            MoneyAmount.ofMinorUnits(150)),
                    FailurePoint.NONE);

            Escrow partial = ledger.openEscrow(request);
            assertEquals(EscrowState.PARTIALLY_SETTLED, partial.state());
            assertEquals(MoneyAmount.ofMinorUnits(150), partial.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(250), partial.remainingAmount());

            coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("public-works"),
                            "road-final-milestone",
                            escrow.reservationId(),
                            new AccountId("organization:road-builder:fiscal"),
                            MoneyAmount.ofMinorUnits(250)),
                    FailurePoint.NONE);

            Escrow settled = ledger.openEscrow(request);
            assertEquals(EscrowState.SETTLED, settled.state());
            assertEquals(MoneyAmount.ofMinorUnits(400), settled.settledAmount());
            assertEquals(MoneyAmount.ZERO, settled.remainingAmount());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void releasingTheReservationReleasesTheEscrowAndFreesItsRemainder() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        OpenEscrow request = new OpenEscrow(
                new ServiceIdentity("public-works"),
                "cancelled-escrow",
                treasury,
                MoneyAmount.ofMinorUnits(400),
                "contract:cancelled-4",
                "Cancelled public contract",
                NOW.plusSeconds(86_400));

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, treasury);
            Escrow escrow = ledger.openEscrow(request);

            ledger.release(new ReleaseReservation(
                    new ServiceIdentity("public-works"),
                    "release-cancelled-escrow",
                    escrow.reservationId(),
                    "Contract cancelled before settlement"));

            Escrow released = ledger.openEscrow(request);
            assertEquals(EscrowState.RELEASED, released.state());
            assertEquals(MoneyAmount.ZERO, released.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(400), released.remainingAmount());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
            assertEquals(MoneyAmount.ofMinorUnits(1_000), ledger.availableBalance(treasury));
        }
    }

    @Test
    void escrowExpiresOnlyAtItsDeadlineAndReplayDoesNotReleaseTwice() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        Instant deadline = NOW.plusSeconds(60);
        OpenEscrow request = new OpenEscrow(
                new ServiceIdentity("public-works"),
                "expiring-escrow",
                treasury,
                MoneyAmount.ofMinorUnits(400),
                "contract:expiring-2",
                "Expiring public contract",
                deadline);
        ExpireEscrow expiryRequest;
        Escrow expired;

        try (CivicDatabase database = database()) {
            FiscalLedger beforeDeadline = ledger(database, treasury, NOW);
            Escrow escrow = beforeDeadline.openEscrow(request);
            expiryRequest = new ExpireEscrow(
                    new ServiceIdentity("civiceconomy-server"),
                    "expire-contract-2",
                    escrow.escrowId());

            assertThrows(IllegalStateException.class, () -> beforeDeadline.expireEscrow(expiryRequest));
            assertEquals(MoneyAmount.ofMinorUnits(400), beforeDeadline.reservedBalance(treasury));

            FiscalLedger atDeadline = ledger(database, treasury, deadline);
            expired = atDeadline.expireEscrow(expiryRequest);
            assertEquals(EscrowState.EXPIRED, expired.state());
            assertEquals(MoneyAmount.ZERO, atDeadline.reservedBalance(treasury));
        }

        try (CivicDatabase reopened = database()) {
            FiscalLedger ledger = ledger(reopened, treasury, deadline.plusSeconds(60));
            assertEquals(expired, ledger.expireEscrow(expiryRequest));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
            assertEquals(MoneyAmount.ofMinorUnits(1_000), ledger.availableBalance(treasury));
        }
    }

    private FiscalLedger ledger(CivicDatabase database, AccountId treasury) {
        return ledger(database, treasury, NOW);
    }

    private FiscalLedger ledger(CivicDatabase database, AccountId treasury, Instant now) {
        return new FiscalLedger(
                database,
                account -> account.equals(treasury)
                        ? MoneyAmount.ofMinorUnits(1_000)
                        : MoneyAmount.ZERO,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("escrow.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("4b617458-7f03-4fd2-a94e-4dc37ecbd682"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
