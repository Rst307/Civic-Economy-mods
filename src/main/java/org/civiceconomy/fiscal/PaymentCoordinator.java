package org.civiceconomy.fiscal;

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
            database.commitPayment(transaction.transactionId(), transaction.reservationId());
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
            }
            database.commitPayment(transaction.transactionId(), transaction.reservationId());
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

    private static PaymentTransaction toTransaction(StoredPaymentTransaction stored) {
        return new PaymentTransaction(
                stored.transactionId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.reservationId(),
                new AccountId(stored.sourceAccount()),
                new AccountId(stored.recipientAccount()),
                MoneyAmount.ofMinorUnits(stored.amountMinorUnits()),
                TransactionState.valueOf(stored.state()));
    }
}
