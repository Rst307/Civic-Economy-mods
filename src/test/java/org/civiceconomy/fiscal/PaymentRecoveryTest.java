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
