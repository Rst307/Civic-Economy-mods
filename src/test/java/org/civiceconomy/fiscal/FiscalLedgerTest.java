package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalLedgerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void replayingAReservationRequestReturnsOneDurableHold() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountBalances balances = accountId -> MoneyAmount.ofMinorUnits(1_000);
        ReserveFunds request = new ReserveFunds(
                new ServiceIdentity("civiceconomy"),
                "territory-prepay-42",
                treasury,
                MoneyAmount.ofMinorUnits(300),
                "First territory maintenance cycle");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = new FiscalLedger(database, balances);

            Reservation first = ledger.reserve(request);
            Reservation replay = ledger.reserve(request);

            assertEquals(first, replay);
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(treasury));
            assertEquals(MoneyAmount.ofMinorUnits(700), ledger.availableBalance(treasury));
        }
    }

    @Test
    void replayReturnsTheCommittedReservationEvenWhenTheCurrentBalanceChanged() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AtomicLong currentBalance = new AtomicLong(1_000);
        AccountBalances balances = accountId -> MoneyAmount.ofMinorUnits(currentBalance.get());
        ReserveFunds request = new ReserveFunds(
                new ServiceIdentity("civiceconomy"),
                "stable-replay-7",
                treasury,
                MoneyAmount.ofMinorUnits(300),
                "Committed hold");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = new FiscalLedger(database, balances);
            Reservation committed = ledger.reserve(request);
            currentBalance.set(0);

            assertEquals(committed, ledger.reserve(request));
        }
    }

    @Test
    void insufficientFundsDoNotCreateAReservation() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ReserveFunds request = new ReserveFunds(
                new ServiceIdentity("civiceconomy"),
                "too-large-1",
                treasury,
                MoneyAmount.ofMinorUnits(1_001),
                "Unaffordable hold");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = new FiscalLedger(database, ignored -> MoneyAmount.ofMinorUnits(1_000));

            assertThrows(InsufficientAvailableBalanceException.class, () -> ledger.reserve(request));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void reusingARequestIdWithAnotherPayloadIsRejected() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ServiceIdentity service = new ServiceIdentity("civiceconomy");
        ReserveFunds original = new ReserveFunds(
                service,
                "collision-9",
                treasury,
                MoneyAmount.ofMinorUnits(300),
                "Original hold");
        ReserveFunds conflicting = new ReserveFunds(
                service,
                "collision-9",
                treasury,
                MoneyAmount.ofMinorUnits(301),
                "Changed hold");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = new FiscalLedger(database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            ledger.reserve(original);

            assertThrows(IdempotencyConflictException.class, () -> ledger.reserve(conflicting));
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(treasury));
        }
    }

    @Test
    void concurrentReservationsCannotOversubscribeTheSameBalance() throws Exception {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        CountDownLatch bothReadBalance = new CountDownLatch(2);
        AccountBalances balances = ignored -> {
            bothReadBalance.countDown();
            try {
                bothReadBalance.await(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return MoneyAmount.ofMinorUnits(1_000);
        };

        try (CivicDatabase database = database(); var executor = Executors.newFixedThreadPool(2)) {
            FiscalLedger ledger = new FiscalLedger(database, balances);
            var first = executor.submit(() -> reserveResult(ledger, "concurrent-1", treasury));
            var second = executor.submit(() -> reserveResult(ledger, "concurrent-2", treasury));

            int successCount = first.get() + second.get();

            assertEquals(1, successCount);
            assertEquals(MoneyAmount.ofMinorUnits(700), ledger.reservedBalance(treasury));
        }
    }

    @Test
    void releasingAReservationIsDurableAndIdempotentAcrossRestart() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ReleaseReservation releaseRequest;
        ReservationRelease released;

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = new FiscalLedger(database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "release-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Cancelled public works"));
            releaseRequest = new ReleaseReservation(
                    new ServiceIdentity("civiceconomy"),
                    "release-request",
                    reservation.reservationId(),
                    "Project cancelled before payment");

            released = ledger.release(releaseRequest);

            assertEquals(released, ledger.release(releaseRequest));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
            assertEquals(MoneyAmount.ofMinorUnits(1_000), ledger.availableBalance(treasury));
        }

        try (CivicDatabase reopened = database()) {
            FiscalLedger ledger = new FiscalLedger(reopened, ignored -> MoneyAmount.ofMinorUnits(1_000));

            assertEquals(released, ledger.release(releaseRequest));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void releaseRequestCannotBeReusedWithAnotherReason() {
        AccountId treasury = new AccountId("nation:aurora:treasury");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = new FiscalLedger(database, ignored -> MoneyAmount.ofMinorUnits(1_000));
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "release-conflict-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Cancelled contract"));
            ServiceIdentity identity = new ServiceIdentity("civiceconomy");
            ledger.release(new ReleaseReservation(
                    identity, "release-conflict", reservation.reservationId(), "Original reason"));

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> ledger.release(new ReleaseReservation(
                            identity,
                            "release-conflict",
                            reservation.reservationId(),
                            "Changed reason")));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    private static int reserveResult(FiscalLedger ledger, String requestId, AccountId treasury) {
        try {
            ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    requestId,
                    treasury,
                    MoneyAmount.ofMinorUnits(700),
                    "Concurrent hold"));
            return 1;
        } catch (InsufficientAvailableBalanceException expected) {
            return 0;
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("fiscal.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("4b617458-7f03-4fd2-a94e-4dc37ecbd682"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
