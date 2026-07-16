package org.civiceconomy.fiscal;

import java.util.UUID;
import java.util.function.Function;
import org.civiceconomy.persistence.CivicDatabase;

public final class FiscalBillPaymentCoordinator {
    private final CivicDatabase database;
    private final ExternalPayments externalPayments;
    private final Function<FiscalAuthorization, FiscalServiceSession> sessionFactory;

    public FiscalBillPaymentCoordinator(
            CivicDatabase database, ExternalPayments externalPayments) {
        this(
                database,
                externalPayments,
                authorization -> authorization.openSession(
                        FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY));
    }

    FiscalBillPaymentCoordinator(
            CivicDatabase database,
            ExternalPayments externalPayments,
            Function<FiscalAuthorization, FiscalServiceSession> sessionFactory) {
        if (database == null || externalPayments == null || sessionFactory == null) {
            throw new IllegalArgumentException(
                    "Fiscal Bill payment dependencies cannot be null");
        }
        this.database = database;
        this.externalPayments = externalPayments;
        this.sessionFactory = sessionFactory;
    }

    public PaymentTransaction pay(UUID actorPlayerId, UUID billId, String requestId) {
        PreparedFiscalBillPayment prepared = prepare(actorPlayerId, billId, requestId);
        applyExternal(prepared);
        return commit(prepared);
    }

    public PreparedFiscalBillPayment prepare(
            UUID actorPlayerId, UUID billId, String requestId) {
        if (actorPlayerId == null || billId == null) {
            throw new IllegalArgumentException("Fiscal Bill payment identity cannot be null");
        }
        AccountId payerAccount = new AccountId("player:" + actorPlayerId);
        var storedBill = database.fiscalBillForPayer(billId, payerAccount.value());
        if (storedBill == null) {
            throw new SecurityException("Fiscal Bill is not payable by this player");
        }
        FiscalBill bill = FiscalLedger.toFiscalBill(storedBill);
        UUID escrowId = bill.escrowId().orElseThrow(() ->
                new IllegalStateException("Fiscal Bill has not been funded"));
        Escrow escrow = FiscalLedger.toEscrow(database.escrow(escrowId));

        MoneyAmount amount = bill.remainingAmount();
        var existing = database.paymentTransaction(requestId);
        if (existing != null
                && existing.serviceIdentity()
                        .equals(FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY.value())) {
            amount = MoneyAmount.ofMinorUnits(existing.amountMinorUnits());
        }
        if (amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalStateException("Fiscal Bill has no unpaid amount");
        }

        FiscalAuthorization authorization = new FiscalAuthorization(database);
        new FiscalBillPaymentServiceProvisioner(authorization)
                .ensureAuthorized(payerAccount);
        FiscalServiceSession session = sessionFactory.apply(authorization);
        PaymentTransaction transaction = PaymentCoordinator.authorized(
                        database, externalPayments, session)
                .prepare(
                        new SettleReservation(
                                FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY,
                                requestId,
                                escrow.reservationId(),
                                bill.beneficiaryAccount(),
                                amount));
        return new PreparedFiscalBillPayment(transaction, session);
    }

    public void applyExternal(PreparedFiscalBillPayment prepared) {
        requirePrepared(prepared);
        PaymentCoordinator.authorized(database, externalPayments, prepared.session())
                .applyExternal(prepared.transaction());
    }

    public PaymentTransaction commit(PreparedFiscalBillPayment prepared) {
        requirePrepared(prepared);
        return PaymentCoordinator.authorized(database, externalPayments, prepared.session())
                .confirmExternalApplied(prepared.transaction());
    }

    private static void requirePrepared(PreparedFiscalBillPayment prepared) {
        if (prepared == null) {
            throw new IllegalArgumentException("Prepared Fiscal Bill payment cannot be null");
        }
        prepared.session().requireIdentity(
                FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY);
    }
}
