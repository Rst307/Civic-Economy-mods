package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaymentRecoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restartAfterExternalPaymentCommitsWithoutPayingTwice() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");
        AtomicInteger externalApplyCalls = new AtomicInteger();
        Set<UUID> appliedTransactions = new HashSet<>();
        ExternalPayments externalPayments = payment -> {
            externalApplyCalls.incrementAndGet();
            appliedTransactions.add(payment.transactionId());
        };
        Reservation reservation;

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database);
            reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "budget-hold-1",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Public works milestone"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, externalPayments);

            assertThrows(SimulatedCrash.class, () -> coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "milestone-payment-1",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.AFTER_EXTERNAL_APPLIED));
        }

        try (CivicDatabase reopened = database()) {
            PaymentCoordinator recovery = new PaymentCoordinator(reopened, payment -> {
                externalApplyCalls.incrementAndGet();
                if (!appliedTransactions.add(payment.transactionId())) {
                    throw new AssertionError("External payment was applied twice");
                }
            });

            recovery.recoverIncomplete();

            assertEquals(1, externalApplyCalls.get());
            assertEquals(TransactionState.CIVIC_COMMITTED, recovery.transaction("milestone-payment-1").state());
            assertEquals(MoneyAmount.ZERO, ledger(reopened).reservedBalance(treasury));
        }
    }

    @Test
    void ambiguousExternalResultRetriesTheSameIdempotencyKey() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");
        AtomicInteger externalAttempts = new AtomicInteger();
        AtomicInteger economicEffects = new AtomicInteger();
        Set<UUID> appliedTransactions = new HashSet<>();
        ExternalPayments idempotentExternal = payment -> {
            externalAttempts.incrementAndGet();
            if (appliedTransactions.add(payment.transactionId())) {
                economicEffects.incrementAndGet();
            }
        };

        try (CivicDatabase database = database()) {
            Reservation reservation = ledger(database).reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "ambiguous-hold-1",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Ambiguous payment hold"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, idempotentExternal);

            assertThrows(SimulatedCrash.class, () -> coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "ambiguous-payment-1",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD));
        }

        try (CivicDatabase reopened = database()) {
            PaymentCoordinator recovery = new PaymentCoordinator(reopened, idempotentExternal);
            recovery.recoverIncomplete();

            assertEquals(2, externalAttempts.get());
            assertEquals(1, economicEffects.get());
            assertEquals(TransactionState.CIVIC_COMMITTED, recovery.transaction("ambiguous-payment-1").state());
        }
    }

    @Test
    void reservationWithAmbiguousPaymentCannotBeReleased() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "ambiguous-release-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Payment may already be external"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});
            assertThrows(SimulatedCrash.class, () -> coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "ambiguous-before-release",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD));

            assertThrows(
                    ReservationHasPendingPaymentException.class,
                    () -> ledger.release(new ReleaseReservation(
                            new ServiceIdentity("civiceconomy"),
                            "unsafe-release",
                            reservation.reservationId(),
                            "Caller tried to cancel an ambiguous payment")));
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(treasury));
        }
    }

    @Test
    void settledReservationCannotBeReleased() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "settled-release-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Already paid"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});
            coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "settled-before-release",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.NONE);

            assertThrows(
                    ReservationNotActiveException.class,
                    () -> ledger.release(new ReleaseReservation(
                            new ServiceIdentity("civiceconomy"),
                            "release-after-settlement",
                            reservation.reservationId(),
                            "Too late to cancel")));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void partialSettlementsReduceOnlyTheRemainingHold() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId firstRecipient = new AccountId("player:river");
        AccountId secondRecipient = new AccountId("player:stone");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "partial-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(500),
                    "Two milestone contract"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});
            SettleReservation firstRequest = new SettleReservation(
                    new ServiceIdentity("civiceconomy"),
                    "partial-first",
                    reservation.reservationId(),
                    firstRecipient,
                    MoneyAmount.ofMinorUnits(200));

            PaymentTransaction first = coordinator.settle(firstRequest, FailurePoint.NONE);

            assertEquals(TransactionState.CIVIC_COMMITTED, first.state());
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(treasury));
            assertEquals(first, coordinator.settle(firstRequest, FailurePoint.NONE));
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(treasury));

            PaymentTransaction second = coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "partial-second",
                            reservation.reservationId(),
                            secondRecipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.NONE);

            assertEquals(TransactionState.CIVIC_COMMITTED, second.state());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void releasingAfterPartialSettlementFreesOnlyTheRemainder() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "partial-release-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(500),
                    "Partially completed contract"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});
            coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "partial-before-release",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(200)),
                    FailurePoint.NONE);
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(treasury));

            ledger.release(new ReleaseReservation(
                    new ServiceIdentity("civiceconomy"),
                    "release-partial-remainder",
                    reservation.reservationId(),
                    "Remaining milestone cancelled"));

            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(treasury));
        }
    }

    @Test
    void settlementCannotExceedReservationRemainder() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "remainder-limit-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(500),
                    "Bounded milestones"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});
            coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "remainder-first",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(200)),
                    FailurePoint.NONE);

            assertThrows(
                    InsufficientReservationRemainderException.class,
                    () -> coordinator.settle(
                            new SettleReservation(
                                    new ServiceIdentity("civiceconomy"),
                                    "remainder-too-large",
                                    reservation.reservationId(),
                                    recipient,
                                    MoneyAmount.ofMinorUnits(301)),
                            FailurePoint.NONE));
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(treasury));
        }
    }

    @Test
    void partialSettlementRecoveryPreservesTheUnpaidRemainder() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");

        try (CivicDatabase database = database()) {
            Reservation reservation = ledger(database).reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "partial-recovery-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(500),
                    "Recoverable first milestone"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});

            assertThrows(SimulatedCrash.class, () -> coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "partial-recovery-payment",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(200)),
                    FailurePoint.AFTER_EXTERNAL_APPLIED));
        }

        try (CivicDatabase reopened = database()) {
            PaymentCoordinator recovery = new PaymentCoordinator(reopened, ignored -> {
                throw new AssertionError("Committed external partial payment must not be repeated");
            });

            recovery.recoverIncomplete();

            assertEquals(
                    TransactionState.CIVIC_COMMITTED,
                    recovery.transaction("partial-recovery-payment").state());
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger(reopened).reservedBalance(treasury));
        }
    }

    private FiscalLedger ledger(CivicDatabase database) {
        return new FiscalLedger(database, ignored -> MoneyAmount.ofMinorUnits(1_000));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("recovery.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("4b617458-7f03-4fd2-a94e-4dc37ecbd682"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
