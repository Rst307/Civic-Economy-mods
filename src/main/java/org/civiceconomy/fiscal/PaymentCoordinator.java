package org.civiceconomy.fiscal;

import java.util.Optional;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.PendingReservationPaymentException;
import org.civiceconomy.persistence.ReservationRemainderExceededException;
import org.civiceconomy.persistence.StoredPaymentTransaction;

public final class PaymentCoordinator {
    private final CivicDatabase database;
    private final ExternalPayments externalPayments;

    public PaymentCoordinator(CivicDatabase database, ExternalPayments externalPayments) {
        this.database = database;
        this.externalPayments = externalPayments;
    }

    public PaymentTransaction settle(SettleReservation request, FailurePoint failurePoint) {
        StoredPaymentTransaction stored;
        try {
            stored = database.preparePayment(
                    request.serviceIdentity().value(),
                    request.requestId(),
                    request.reservationId(),
                    request.recipientAccount().value(),
                    request.amount().minorUnits());
        } catch (ReservationRemainderExceededException exceeded) {
            throw new InsufficientReservationRemainderException(
                    exceeded.reservationId(),
                    MoneyAmount.ofMinorUnits(exceeded.requestedMinorUnits()),
                    MoneyAmount.ofMinorUnits(exceeded.remainingMinorUnits()));
        } catch (PendingReservationPaymentException pending) {
            throw new ReservationHasPendingPaymentException(pending.reservationId());
        }
        PaymentTransaction transaction = toTransaction(stored);
        if (transaction.kind() != PaymentKind.PAYMENT
                || !transaction.reservationId().equals(request.reservationId())
                || !transaction.recipientAccount().equals(request.recipientAccount())
                || !transaction.amount().equals(request.amount())) {
            throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
        }
        return applyAndCommit(transaction, failurePoint);
    }

    public PaymentTransaction refund(RefundPayment request, FailurePoint failurePoint) {
        StoredPaymentTransaction stored;
        try {
            stored = database.prepareRefund(
                    request.serviceIdentity().value(),
                    request.requestId(),
                    request.originalTransactionId(),
                    request.amount().minorUnits(),
                    request.reason());
        } catch (ReservationRemainderExceededException exceeded) {
            throw new InsufficientRefundableAmountException(
                    exceeded.reservationId(),
                    MoneyAmount.ofMinorUnits(exceeded.requestedMinorUnits()),
                    MoneyAmount.ofMinorUnits(exceeded.remainingMinorUnits()));
        }
        PaymentTransaction transaction = toTransaction(stored);
        if (transaction.kind() != PaymentKind.REFUND
                || !transaction.parentTransactionId().equals(Optional.of(request.originalTransactionId()))
                || !transaction.amount().equals(request.amount())
                || !transaction.reason().equals(Optional.of(request.reason()))) {
            throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
        }
        return applyAndCommit(transaction, failurePoint);
    }

    private PaymentTransaction applyAndCommit(PaymentTransaction transaction, FailurePoint failurePoint) {
        if (transaction.state() == TransactionState.PREPARED) {
            externalPayments.apply(externalPayment(transaction));
            if (failurePoint == FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD) {
                throw new SimulatedCrash(failurePoint);
            }
            database.markExternalApplied(transaction.transactionId());
            transaction = transaction(transaction.requestId());
            if (failurePoint == FailurePoint.AFTER_EXTERNAL_APPLIED) {
                throw new SimulatedCrash(failurePoint);
            }
        }
        if (transaction.state() == TransactionState.EXTERNAL_APPLIED) {
            commit(transaction);
            transaction = transaction(transaction.requestId());
        }
        return transaction;
    }

    public void recoverIncomplete() {
        for (StoredPaymentTransaction stored : database.incompletePayments()) {
            PaymentTransaction transaction = toTransaction(stored);
            if (transaction.state() == TransactionState.PREPARED) {
                externalPayments.apply(externalPayment(transaction));
                database.markExternalApplied(transaction.transactionId());
                transaction = transaction(transaction.requestId());
            }
            commit(transaction);
        }
    }

    public PaymentTransaction transaction(String requestId) {
        StoredPaymentTransaction stored = database.paymentTransaction(requestId);
        if (stored == null) {
            throw new IllegalArgumentException("Unknown payment request " + requestId);
        }
        return toTransaction(stored);
    }

    private static ExternalPayment externalPayment(PaymentTransaction transaction) {
        return new ExternalPayment(
                transaction.transactionId(),
                transaction.sourceAccount(),
                transaction.recipientAccount(),
                transaction.amount());
    }

    private void commit(PaymentTransaction transaction) {
        if (transaction.kind() == PaymentKind.PAYMENT) {
            database.commitPayment(transaction.transactionId(), transaction.reservationId());
        } else {
            database.commitRefund(
                    transaction.transactionId(), transaction.parentTransactionId().orElseThrow());
        }
    }

    private static PaymentTransaction toTransaction(StoredPaymentTransaction stored) {
        return new PaymentTransaction(
                stored.transactionId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.reservationId(),
                new AccountId(stored.sourceAccount()),
                new AccountId(stored.recipientAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                PaymentKind.valueOf(stored.kind()),
                Optional.ofNullable(stored.parentTransactionId()),
                MoneyAmount.ofMinorUnits(stored.refundedMinorUnits()),
                Optional.ofNullable(stored.reason()),
                TransactionState.valueOf(stored.state()));
    }
}
