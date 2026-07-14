package org.civiceconomy.fiscal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.PendingReservationPaymentException;
import org.civiceconomy.persistence.ReservationRemainderExceededException;
import org.civiceconomy.persistence.RequiredRecipientMismatchException;
import org.civiceconomy.persistence.StoredPaymentCompensation;
import org.civiceconomy.persistence.StoredPaymentTransaction;

public final class PaymentCoordinator {
    private final CivicDatabase database;
    private final ExternalPayments externalPayments;
    private final FiscalAuthorization authorization;
    private final FiscalServiceSession session;

    PaymentCoordinator(CivicDatabase database, ExternalPayments externalPayments) {
        this(database, externalPayments, null, null);
    }

    private PaymentCoordinator(
            CivicDatabase database,
            ExternalPayments externalPayments,
            FiscalAuthorization authorization,
            FiscalServiceSession session) {
        this.database = database;
        this.externalPayments = externalPayments;
        this.authorization = authorization;
        this.session = session;
    }

    static PaymentCoordinator authorized(
            CivicDatabase database, ExternalPayments externalPayments) {
        return new PaymentCoordinator(
                database, externalPayments, new FiscalAuthorization(database), null);
    }

    public static PaymentCoordinator authorized(
            CivicDatabase database,
            ExternalPayments externalPayments,
            FiscalServiceSession session) {
        java.util.Objects.requireNonNull(session, "Fiscal service session cannot be null");
        return new PaymentCoordinator(
                database, externalPayments, new FiscalAuthorization(database), session);
    }

    public PaymentTransaction settle(SettleReservation request, FailurePoint failurePoint) {
        requireSessionIdentity(request.serviceIdentity());
        if (authorization != null) {
            var reservation = database.reservationRecord(request.reservationId());
            if (reservation == null) {
                throw new IllegalArgumentException("Unknown Reservation " + request.reservationId());
            }
            require(
                    request.serviceIdentity(),
                    FiscalCapability.SETTLE_PAYMENT,
                    new AccountId(reservation.sourceAccount()));
        }
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
        } catch (RequiredRecipientMismatchException mismatch) {
            throw new PaymentRecipientMismatchException(
                    mismatch.reservationId(),
                    new AccountId(mismatch.expectedRecipient()),
                    new AccountId(mismatch.actualRecipient()));
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
        requireSessionIdentity(request.serviceIdentity());
        StoredPaymentTransaction original = database.paymentTransaction(request.originalTransactionId());
        if (original == null) {
            throw new IllegalArgumentException(
                    "Unknown payment transaction " + request.originalTransactionId());
        }
        require(
                request.serviceIdentity(),
                FiscalCapability.REFUND_PAYMENT,
                new AccountId(original.recipientAccount()));
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

    public PaymentTransaction refundTerritoryClaimPermit(
            RefundTerritoryClaimPermitPayment request, FailurePoint failurePoint) {
        requireSessionIdentity(request.serviceIdentity());
        var permit = database.territoryClaimPermit(request.permitId());
        if (permit == null) {
            throw new IllegalArgumentException(
                    "Unknown Territory Claim Permit " + request.permitId());
        }
        StoredPaymentTransaction original =
                database.paymentTransaction(permit.prepaymentTransactionId());
        if (original == null) {
            throw new IllegalStateException(
                    "Territory Claim Permit has no prepayment transaction");
        }
        require(
                request.serviceIdentity(),
                FiscalCapability.REFUND_PAYMENT,
                new AccountId(original.recipientAccount()));
        StoredPaymentTransaction stored = database.prepareTerritoryClaimPermitRefund(
                request.serviceIdentity().value(),
                request.requestId(),
                request.permitId(),
                request.reason());
        PaymentTransaction transaction = toTransaction(stored);
        if (transaction.kind() != PaymentKind.REFUND
                || !transaction.parentTransactionId()
                        .equals(Optional.of(original.transactionId()))
                || !transaction.amount()
                        .equals(MoneyAmount.ofMinorUnits(permit.prepaymentMinorUnits()))
                || !transaction.reason().equals(Optional.of(request.reason()))) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
        return applyAndCommit(transaction, failurePoint);
    }

    public PaymentTransaction compensate(CompensatePayment request, FailurePoint failurePoint) {
        requireSessionIdentity(request.serviceIdentity());
        StoredPaymentTransaction original = database.paymentTransaction(request.transactionId());
        if (original == null) {
            throw new IllegalArgumentException(
                    "Unknown payment transaction " + request.transactionId());
        }
        require(
                request.serviceIdentity(),
                FiscalCapability.COMPENSATE_PAYMENT,
                new AccountId(original.sourceAccount()));
        StoredPaymentCompensation compensation = database.prepareCompensation(
                request.serviceIdentity().value(),
                request.requestId(),
                request.transactionId(),
                request.reason(),
                System.currentTimeMillis());
        if (!compensation.serviceIdentity().equals(request.serviceIdentity().value())
                || !compensation.requestId().equals(request.requestId())
                || !compensation.transactionId().equals(request.transactionId())
                || !compensation.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
        }

        PaymentTransaction transaction = toTransaction(database.paymentTransaction(request.transactionId()));
        if (transaction.state() == TransactionState.COMPENSATING) {
            externalPayments.apply(compensationPayment(transaction, compensation.compensationId()));
            if (failurePoint == FailurePoint.AFTER_COMPENSATION_BEFORE_RECORD) {
                throw new SimulatedCrash(failurePoint);
            }
            database.completeCompensation(transaction.transactionId(), System.currentTimeMillis());
            transaction = toTransaction(database.paymentTransaction(request.transactionId()));
        }
        if (transaction.state() != TransactionState.COMPENSATED) {
            throw new IllegalStateException(
                    "Payment transaction has inconsistent compensation state " + transaction.transactionId());
        }
        return transaction;
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
            commit(transaction, false);
            transaction = transaction(transaction.requestId());
        }
        return transaction;
    }

    public void recoverIncomplete() {
        for (StoredPaymentTransaction stored : database.incompletePayments()) {
            PaymentTransaction transaction = toTransaction(stored);
            if (transaction.state() == TransactionState.COMPENSATING) {
                StoredPaymentCompensation compensation =
                        database.paymentCompensation(transaction.transactionId());
                if (compensation == null) {
                    throw new IllegalStateException(
                            "Compensating payment has no durable compensation request "
                                    + transaction.transactionId());
                }
                externalPayments.apply(compensationPayment(transaction, compensation.compensationId()));
                database.completeCompensation(transaction.transactionId(), System.currentTimeMillis());
                continue;
            }
            if (transaction.state() == TransactionState.PREPARED) {
                externalPayments.apply(externalPayment(transaction));
                database.markExternalAppliedDuringRecovery(
                        transaction.transactionId(), System.currentTimeMillis());
                transaction = transaction(transaction.requestId());
            }
            commit(transaction, true);
        }
    }

    List<RecoveryAuditEntry> recoveryAudit(UUID transactionId) {
        return database.recoveryAudit(transactionId).stream()
                .map(stored -> new RecoveryAuditEntry(
                        stored.auditId(),
                        stored.transactionId(),
                        RecoveryAction.valueOf(stored.action()),
                        new ServiceIdentity(stored.serviceIdentity()),
                        stored.detail(),
                        stored.recordedAt()))
                .toList();
    }

    public List<RecoveryAuditEntry> recoveryAudit(
            ServiceIdentity serviceIdentity, UUID transactionId) {
        requireSessionIdentity(serviceIdentity);
        StoredPaymentTransaction transaction = database.paymentTransaction(transactionId);
        if (transaction == null) {
            throw new IllegalArgumentException("Unknown payment transaction " + transactionId);
        }
        require(
                serviceIdentity,
                FiscalCapability.READ_ACCOUNT,
                new AccountId(transaction.sourceAccount()));
        return recoveryAudit(transactionId);
    }

    PaymentTransaction transaction(String requestId) {
        StoredPaymentTransaction stored = database.paymentTransaction(requestId);
        if (stored == null) {
            throw new IllegalArgumentException("Unknown payment request " + requestId);
        }
        return toTransaction(stored);
    }

    public PaymentTransaction transaction(
            ServiceIdentity serviceIdentity, String requestId) {
        requireSessionIdentity(serviceIdentity);
        StoredPaymentTransaction stored = database.paymentTransaction(requestId);
        if (stored == null) {
            throw new IllegalArgumentException("Unknown payment request " + requestId);
        }
        require(
                serviceIdentity,
                FiscalCapability.READ_ACCOUNT,
                new AccountId(stored.sourceAccount()));
        return toTransaction(stored);
    }

    private static ExternalPayment externalPayment(PaymentTransaction transaction) {
        return new ExternalPayment(
                transaction.transactionId(),
                transaction.sourceAccount(),
                transaction.recipientAccount(),
                transaction.amount());
    }

    private static ExternalPayment compensationPayment(
            PaymentTransaction transaction, UUID compensationTransactionId) {
        return new ExternalPayment(
                compensationTransactionId,
                transaction.recipientAccount(),
                transaction.sourceAccount(),
                transaction.amount());
    }

    private void commit(PaymentTransaction transaction, boolean recovery) {
        if (transaction.kind() == PaymentKind.PAYMENT) {
            if (recovery) {
                database.commitPaymentDuringRecovery(
                        transaction.transactionId(),
                        transaction.reservationId(),
                        System.currentTimeMillis());
            } else {
                database.commitPayment(transaction.transactionId(), transaction.reservationId());
            }
        } else {
            if (recovery) {
                database.commitRefundDuringRecovery(
                        transaction.transactionId(),
                        transaction.parentTransactionId().orElseThrow(),
                        System.currentTimeMillis());
            } else {
                database.commitRefund(
                        transaction.transactionId(), transaction.parentTransactionId().orElseThrow());
            }
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

    private void require(
            ServiceIdentity serviceIdentity, FiscalCapability capability, AccountId accountId) {
        if (authorization != null) {
            requireSessionIdentity(serviceIdentity);
            authorization.require(serviceIdentity, capability, accountId);
        }
    }

    private void requireSessionIdentity(ServiceIdentity serviceIdentity) {
        if (session != null) {
            session.requireIdentity(serviceIdentity);
        }
    }
}
