package org.civiceconomy.fiscal;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.civiceconomy.persistence.CivicDatabase;

public final class BudgetDisbursementPaymentCoordinator {
    private final CivicDatabase database;
    private final ExternalPayments externalPayments;
    private final Function<FiscalAuthorization, FiscalServiceSession> sessionFactory;

    public BudgetDisbursementPaymentCoordinator(
            CivicDatabase database, ExternalPayments externalPayments) {
        this(
                database,
                externalPayments,
                authorization -> authorization.openSession(
                        BudgetDisbursementPaymentServiceProvisioner.SERVICE_IDENTITY));
    }

    BudgetDisbursementPaymentCoordinator(
            CivicDatabase database,
            ExternalPayments externalPayments,
            Function<FiscalAuthorization, FiscalServiceSession> sessionFactory) {
        if (database == null || externalPayments == null || sessionFactory == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement payment dependencies cannot be null");
        }
        this.database = database;
        this.externalPayments = externalPayments;
        this.sessionFactory = sessionFactory;
    }

    public PaymentTransaction pay(UUID approvalRequestId) {
        PreparedBudgetDisbursementPayment prepared = prepare(approvalRequestId);
        applyExternal(prepared);
        return commit(prepared);
    }

    public PreparedBudgetDisbursementPayment prepare(UUID approvalRequestId) {
        if (approvalRequestId == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval cannot be null");
        }
        BudgetDisbursementApproval approval =
                new BudgetDisbursementApprovalRegistry(
                                database, java.time.Clock.systemUTC())
                        .find(approvalRequestId);
        if (!("APPROVED".equals(approval.state())
                || "EXECUTED".equals(approval.state()))) {
            throw new IllegalStateException(
                    "Budget Disbursement approval is not executable: " + approval.state());
        }
        var storedBudget = database.budgetForSourceAccount(
                approval.budgetId(),
                "nation:" + approval.nationId().value() + ":treasury");
        if (storedBudget == null || storedBudget.escrowId() == null) {
            throw new SecurityException(
                    "Budget Disbursement approval no longer references an exact funded Budget");
        }
        Escrow escrow = FiscalLedger.toEscrow(database.escrow(storedBudget.escrowId()));
        AccountId treasury = new AccountId(storedBudget.sourceAccount());
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        new BudgetDisbursementPaymentServiceProvisioner(authorization)
                .ensureAuthorized(treasury);
        FiscalServiceSession session = sessionFactory.apply(authorization);
        PaymentTransaction transaction = PaymentCoordinator.authorized(
                        database, externalPayments, session)
                .prepare(new SettleReservation(
                        BudgetDisbursementPaymentServiceProvisioner.SERVICE_IDENTITY,
                        approval.requestId(),
                        escrow.reservationId(),
                        approval.recipientAccount(),
                        approval.amount()));
        return new PreparedBudgetDisbursementPayment(
                approvalRequestId, transaction, session);
    }

    public List<PreparedBudgetDisbursementPayment> prepareApprovedPending() {
        return new BudgetDisbursementApprovalRegistry(
                        database, java.time.Clock.systemUTC())
                .approvedWithoutPayment(
                        NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY)
                .stream()
                .map(approval -> prepare(approval.approvalRequestId()))
                .toList();
    }

    public List<PreparedBudgetDisbursementPayment> recoverableIncomplete() {
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        FiscalServiceSession session = sessionFactory.apply(authorization);
        session.requireIdentity(
                BudgetDisbursementPaymentServiceProvisioner.SERVICE_IDENTITY);
        return database.incompleteBudgetDisbursementPayments(
                        BudgetDisbursementPaymentServiceProvisioner
                                .SERVICE_IDENTITY
                                .value())
                .stream()
                .map(stored -> {
                    var approval = database.budgetDisbursementApproval(
                            stored.serviceIdentity(), stored.requestId());
                    if (approval == null) {
                        throw new IllegalStateException(
                                "Incomplete Budget Disbursement Payment has no exact approval "
                                        + stored.transactionId());
                    }
                    return new PreparedBudgetDisbursementPayment(
                            approval.approvalRequestId(),
                            PaymentCoordinator.toTransaction(stored),
                            session);
                })
                .toList();
    }

    public void applyExternal(PreparedBudgetDisbursementPayment prepared) {
        requirePrepared(prepared);
        PaymentCoordinator.authorized(database, externalPayments, prepared.session())
                .applyExternal(prepared.transaction());
    }

    public PaymentTransaction commit(PreparedBudgetDisbursementPayment prepared) {
        requirePrepared(prepared);
        return PaymentCoordinator.authorized(database, externalPayments, prepared.session())
                .confirmExternalApplied(prepared.transaction());
    }

    public PaymentTransaction commitRecovery(
            PreparedBudgetDisbursementPayment prepared) {
        requirePrepared(prepared);
        return PaymentCoordinator.authorized(database, externalPayments, prepared.session())
                .confirmRecovery(prepared.transaction());
    }

    private static void requirePrepared(PreparedBudgetDisbursementPayment prepared) {
        if (prepared == null) {
            throw new IllegalArgumentException(
                    "Prepared Budget Disbursement payment cannot be null");
        }
        prepared.session().requireIdentity(
                BudgetDisbursementPaymentServiceProvisioner.SERVICE_IDENTITY);
    }
}
