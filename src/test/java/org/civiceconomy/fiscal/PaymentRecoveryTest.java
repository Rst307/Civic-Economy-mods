package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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

    @Test
    void committedPaymentCanBeRefundedExactlyOnce() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");
        AtomicInteger externalCalls = new AtomicInteger();

        try (CivicDatabase database = database()) {
            Reservation reservation = ledger(database).reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "refund-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Refundable grant"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> externalCalls.incrementAndGet());
            PaymentTransaction original = coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "refund-original-payment",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.NONE);
            RefundPayment request = new RefundPayment(
                    new ServiceIdentity("civiceconomy"),
                    "refund-request",
                    original.transactionId(),
                    MoneyAmount.ofMinorUnits(300),
                    "Grant cancelled and returned");

            PaymentTransaction refund = coordinator.refund(request, FailurePoint.NONE);

            assertEquals(TransactionState.CIVIC_COMMITTED, refund.state());
            assertEquals(PaymentKind.REFUND, refund.kind());
            assertEquals(Optional.of(original.transactionId()), refund.parentTransactionId());
            assertEquals(recipient, refund.sourceAccount());
            assertEquals(treasury, refund.recipientAccount());
            assertEquals(MoneyAmount.ofMinorUnits(300), refund.amount());
            assertEquals(refund, coordinator.refund(request, FailurePoint.NONE));
            assertEquals(2, externalCalls.get());
            assertEquals(
                    MoneyAmount.ofMinorUnits(300),
                    coordinator.transaction("refund-original-payment").refundedAmount());
        }
    }

    @Test
    void partialRefundsCannotExceedTheOriginalPayment() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");
        AtomicInteger externalCalls = new AtomicInteger();

        try (CivicDatabase database = database()) {
            Reservation reservation = ledger(database).reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "partial-refund-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(500),
                    "Refundable staged grant"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> externalCalls.incrementAndGet());
            PaymentTransaction original = coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "partial-refund-original",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(500)),
                    FailurePoint.NONE);
            coordinator.refund(
                    new RefundPayment(
                            new ServiceIdentity("civiceconomy"),
                            "partial-refund-first",
                            original.transactionId(),
                            MoneyAmount.ofMinorUnits(200),
                            "First returned portion"),
                    FailurePoint.NONE);
            coordinator.refund(
                    new RefundPayment(
                            new ServiceIdentity("civiceconomy"),
                            "partial-refund-second",
                            original.transactionId(),
                            MoneyAmount.ofMinorUnits(300),
                            "Final returned portion"),
                    FailurePoint.NONE);

            assertEquals(
                    MoneyAmount.ofMinorUnits(500),
                    coordinator.transaction("partial-refund-original").refundedAmount());
            assertThrows(
                    InsufficientRefundableAmountException.class,
                    () -> coordinator.refund(
                            new RefundPayment(
                                    new ServiceIdentity("civiceconomy"),
                                    "partial-refund-too-large",
                                    original.transactionId(),
                                    MoneyAmount.ofMinorUnits(1),
                                    "No refundable remainder"),
                            FailurePoint.NONE));
            assertEquals(3, externalCalls.get());
        }
    }

    @Test
    void ambiguousRefundRecoveryRetriesTheSameTransactionId() {
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
        UUID originalTransactionId;

        try (CivicDatabase database = database()) {
            Reservation reservation = ledger(database).reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "ambiguous-refund-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Refund crash fixture"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, idempotentExternal);
            PaymentTransaction original = coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "ambiguous-refund-original",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.NONE);
            originalTransactionId = original.transactionId();

            assertThrows(SimulatedCrash.class, () -> coordinator.refund(
                    new RefundPayment(
                            new ServiceIdentity("civiceconomy"),
                            "ambiguous-refund-request",
                            original.transactionId(),
                            MoneyAmount.ofMinorUnits(300),
                            "Crash after external refund"),
                    FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD));
        }

        try (CivicDatabase reopened = database()) {
            PaymentCoordinator recovery = new PaymentCoordinator(reopened, idempotentExternal);

            recovery.recoverIncomplete();

            assertEquals(3, externalAttempts.get());
            assertEquals(2, economicEffects.get());
            assertEquals(
                    TransactionState.CIVIC_COMMITTED,
                    recovery.transaction("ambiguous-refund-request").state());
            assertEquals(
                    MoneyAmount.ofMinorUnits(300),
                    recovery.transaction("ambiguous-refund-original").refundedAmount());
            assertEquals(
                    Optional.of(originalTransactionId),
                    recovery.transaction("ambiguous-refund-request").parentTransactionId());
        }
    }

    @Test
    void recordedExternalRefundRecoveryDoesNotPayAgain() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");
        AtomicInteger externalCalls = new AtomicInteger();

        try (CivicDatabase database = database()) {
            Reservation reservation = ledger(database).reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "recorded-refund-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Recorded refund fixture"));
            PaymentCoordinator coordinator =
                    new PaymentCoordinator(database, ignored -> externalCalls.incrementAndGet());
            PaymentTransaction original = coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "recorded-refund-original",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.NONE);

            assertThrows(SimulatedCrash.class, () -> coordinator.refund(
                    new RefundPayment(
                            new ServiceIdentity("civiceconomy"),
                            "recorded-refund-request",
                            original.transactionId(),
                            MoneyAmount.ofMinorUnits(300),
                            "Crash after refund marker"),
                    FailurePoint.AFTER_EXTERNAL_APPLIED));
        }

        try (CivicDatabase reopened = database()) {
            PaymentCoordinator recovery = new PaymentCoordinator(reopened, ignored -> externalCalls.incrementAndGet());

            recovery.recoverIncomplete();

            assertEquals(2, externalCalls.get());
            assertEquals(
                    TransactionState.CIVIC_COMMITTED,
                    recovery.transaction("recorded-refund-request").state());
            assertEquals(
                    MoneyAmount.ofMinorUnits(300),
                    recovery.transaction("recorded-refund-original").refundedAmount());
        }
    }

    @Test
    void compensationRecoversWithoutReversingTwiceAndLeavesAnAuditTrail() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");
        AtomicInteger externalAttempts = new AtomicInteger();
        AtomicInteger recipientNetMinorUnits = new AtomicInteger();
        Set<UUID> appliedTransactions = new HashSet<>();
        ExternalPayments idempotentExternal = payment -> {
            externalAttempts.incrementAndGet();
            if (appliedTransactions.add(payment.transactionId())) {
                if (payment.sourceAccount().equals(treasury)) {
                    recipientNetMinorUnits.addAndGet((int) payment.amount().minorUnits());
                } else {
                    recipientNetMinorUnits.addAndGet((int) -payment.amount().minorUnits());
                }
            }
        };
        UUID transactionId;

        try (CivicDatabase database = database()) {
            Reservation reservation = ledger(database).reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "compensation-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Compensation fixture"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, idempotentExternal);
            assertThrows(SimulatedCrash.class, () -> coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "compensation-original",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.AFTER_EXTERNAL_APPLIED));
            transactionId = coordinator.transaction("compensation-original").transactionId();

            assertThrows(SimulatedCrash.class, () -> coordinator.compensate(
                    new CompensatePayment(
                            new ServiceIdentity("civiceconomy-admin"),
                            "compensate-original",
                            transactionId,
                            "Civic commit cannot safely complete"),
                    FailurePoint.AFTER_COMPENSATION_BEFORE_RECORD));
            assertEquals(0, recipientNetMinorUnits.get());
            assertEquals(TransactionState.COMPENSATING, coordinator.transaction("compensation-original").state());
            assertThrows(
                    ReservationHasPendingPaymentException.class,
                    () -> ledger(database).release(new ReleaseReservation(
                            new ServiceIdentity("civiceconomy"),
                            "unsafe-release-during-compensation",
                            reservation.reservationId(),
                            "Compensation has not completed")));
        }

        try (CivicDatabase reopened = database()) {
            PaymentCoordinator recovery = new PaymentCoordinator(reopened, idempotentExternal);

            recovery.recoverIncomplete();

            assertEquals(3, externalAttempts.get());
            assertEquals(0, recipientNetMinorUnits.get());
            assertEquals(TransactionState.COMPENSATED, recovery.transaction("compensation-original").state());
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger(reopened).reservedBalance(treasury));
            assertEquals(
                    List.of(RecoveryAction.COMPENSATION_STARTED, RecoveryAction.COMPENSATION_COMPLETED),
                    recovery.recoveryAudit(transactionId).stream().map(RecoveryAuditEntry::action).toList());
            assertEquals(
                    new ServiceIdentity("civiceconomy-admin"),
                    recovery.recoveryAudit(transactionId).getFirst().serviceIdentity());
            assertEquals(
                    "Civic commit cannot safely complete",
                    recovery.recoveryAudit(transactionId).getFirst().detail());
        }
    }

    @Test
    void ambiguousPaymentRecoveryIsAuditedExactlyOnce() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("player:river");
        Set<UUID> appliedTransactions = new HashSet<>();
        ExternalPayments idempotentExternal = payment -> appliedTransactions.add(payment.transactionId());
        UUID transactionId;

        try (CivicDatabase database = database()) {
            Reservation reservation = ledger(database).reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy"),
                    "audited-recovery-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Audited recovery fixture"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, idempotentExternal);
            assertThrows(SimulatedCrash.class, () -> coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy"),
                            "audited-recovery-payment",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD));
            transactionId = coordinator.transaction("audited-recovery-payment").transactionId();
        }

        try (CivicDatabase reopened = database()) {
            PaymentCoordinator recovery = new PaymentCoordinator(reopened, idempotentExternal);

            recovery.recoverIncomplete();
            recovery.recoverIncomplete();

            assertEquals(
                    List.of(
                            RecoveryAction.RECOVERY_EXTERNAL_APPLIED,
                            RecoveryAction.RECOVERY_CIVIC_COMMITTED),
                    recovery.recoveryAudit(transactionId).stream().map(RecoveryAuditEntry::action).toList());
            assertEquals(
                    new ServiceIdentity("civiceconomy-recovery"),
                    recovery.recoveryAudit(transactionId).getFirst().serviceIdentity());
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
