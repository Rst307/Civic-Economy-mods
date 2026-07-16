package org.civiceconomy.fiscal;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;
import org.civiceconomy.persistence.CivicDatabase;

public final class FiscalBillCancellationCoordinator {
    private final CivicDatabase database;
    private final Clock clock;
    private final Function<FiscalAuthorization, FiscalServiceSession> sessionFactory;

    public FiscalBillCancellationCoordinator(CivicDatabase database, Clock clock) {
        this(
                database,
                clock,
                authorization -> authorization.openSession(
                        FiscalBillCancellationServiceProvisioner.SERVICE_IDENTITY));
    }

    FiscalBillCancellationCoordinator(
            CivicDatabase database,
            Clock clock,
            Function<FiscalAuthorization, FiscalServiceSession> sessionFactory) {
        if (database == null || clock == null || sessionFactory == null) {
            throw new IllegalArgumentException(
                    "Fiscal Bill cancellation dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
        this.sessionFactory = sessionFactory;
    }

    public FiscalBill cancel(
            UUID actorPlayerId, UUID billId, String requestId, String reason) {
        if (actorPlayerId == null || billId == null) {
            throw new IllegalArgumentException("Fiscal Bill cancellation identity cannot be null");
        }
        AccountId payerAccount = new AccountId("player:" + actorPlayerId);
        var storedBill = database.fiscalBillForPayer(billId, payerAccount.value());
        if (storedBill == null) {
            throw new SecurityException("Fiscal Bill is not cancellable by this player");
        }
        FiscalBill bill = FiscalLedger.toFiscalBill(storedBill);
        UUID escrowId = bill.escrowId().orElseThrow(() ->
                new IllegalStateException("Fiscal Bill has not been funded"));
        Escrow escrow = FiscalLedger.toEscrow(database.escrow(escrowId));

        FiscalAuthorization authorization = new FiscalAuthorization(database);
        new FiscalBillCancellationServiceProvisioner(authorization)
                .ensureAuthorized(payerAccount);
        FiscalServiceSession session = sessionFactory.apply(authorization);
        FiscalLedger.authorized(database, ignored -> MoneyAmount.ZERO, clock, session)
                .release(new ReleaseReservation(
                        FiscalBillCancellationServiceProvisioner.SERVICE_IDENTITY,
                        requestId,
                        escrow.reservationId(),
                        reason));
        return new FiscalBillInspection(database).statusForPayer(actorPlayerId, billId);
    }
}
