package org.civiceconomy.fiscal;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;
import org.civiceconomy.persistence.CivicDatabase;

public final class FiscalBillFundingCoordinator {
    private final CivicDatabase database;
    private final AccountBalances accountBalances;
    private final Clock clock;
    private final Function<FiscalAuthorization, FiscalServiceSession> sessionFactory;

    public FiscalBillFundingCoordinator(
            CivicDatabase database, AccountBalances accountBalances, Clock clock) {
        this(
                database,
                accountBalances,
                clock,
                authorization -> authorization.openSession(
                        FiscalBillFundingServiceProvisioner.SERVICE_IDENTITY));
    }

    FiscalBillFundingCoordinator(
            CivicDatabase database,
            AccountBalances accountBalances,
            Clock clock,
            Function<FiscalAuthorization, FiscalServiceSession> sessionFactory) {
        if (database == null
                || accountBalances == null
                || clock == null
                || sessionFactory == null) {
            throw new IllegalArgumentException(
                    "Fiscal Bill funding dependencies cannot be null");
        }
        this.database = database;
        this.accountBalances = accountBalances;
        this.clock = clock;
        this.sessionFactory = sessionFactory;
    }

    public FiscalBill fund(UUID actorPlayerId, UUID billId, String requestId) {
        if (actorPlayerId == null || billId == null) {
            throw new IllegalArgumentException("Fiscal Bill funding identity cannot be null");
        }
        AccountId payerAccount = new AccountId("player:" + actorPlayerId);
        if (database.fiscalBillForPayer(billId, payerAccount.value()) == null) {
            throw new SecurityException("Fiscal Bill is not payable by this player");
        }
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        new FiscalBillFundingServiceProvisioner(authorization)
                .ensureAuthorized(payerAccount);
        var session = sessionFactory.apply(authorization);
        return FiscalLedger.authorized(database, accountBalances, clock, session)
                .fundBill(new FundFiscalBill(
                        FiscalBillFundingServiceProvisioner.SERVICE_IDENTITY,
                        requestId,
                        billId));
    }
}
