package org.civiceconomy.fiscal;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.persistence.CivicDatabase;

public final class NationBudgetCancellationCoordinator {
    private final CivicDatabase database;
    private final NationProvider nations;
    private final NationFiscalAuthorityRegistry authorities;
    private final Clock clock;
    private final Function<FiscalAuthorization, FiscalServiceSession> sessionFactory;

    public NationBudgetCancellationCoordinator(
            CivicDatabase database,
            NationProvider nations,
            NationFiscalAuthorityRegistry authorities,
            Clock clock) {
        this(
                database,
                nations,
                authorities,
                clock,
                authorization -> authorization.openSession(
                        BudgetFiscalServiceProvisioner.SERVICE_IDENTITY));
    }

    NationBudgetCancellationCoordinator(
            CivicDatabase database,
            NationProvider nations,
            NationFiscalAuthorityRegistry authorities,
            Clock clock,
            Function<FiscalAuthorization, FiscalServiceSession> sessionFactory) {
        if (database == null
                || nations == null
                || authorities == null
                || clock == null
                || sessionFactory == null) {
            throw new IllegalArgumentException(
                    "Nation Budget cancellation dependencies cannot be null");
        }
        this.database = database;
        this.nations = nations;
        this.authorities = authorities;
        this.clock = clock;
        this.sessionFactory = sessionFactory;
    }

    public Budget cancel(
            UUID actorPlayerId, UUID budgetId, String requestId, String reason) {
        if (actorPlayerId == null || budgetId == null) {
            throw new IllegalArgumentException("Budget cancellation identity cannot be null");
        }
        var nationId = nations.findForCitizen(actorPlayerId)
                .orElseThrow(() -> new SecurityException(
                        "Budget cancellation requires effective Citizenship"))
                .nationId();
        authorities.require(
                nationId, actorPlayerId, NationFiscalPermission.APPROVE_BUDGET);
        AccountId treasury = new AccountId("nation:" + nationId.value() + ":treasury");
        if (database.budgetForSourceAccount(budgetId, treasury.value()) == null) {
            throw new SecurityException("Budget is not cancellable by this Nation");
        }
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        new BudgetFiscalServiceProvisioner(authorization).ensureAuthorized(treasury);
        var session = sessionFactory.apply(authorization);
        return FiscalLedger.authorized(database, ignored -> MoneyAmount.ZERO, clock, session)
                .cancelBudget(new CancelBudget(
                        BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                        requestId,
                        budgetId,
                        actorPlayerId,
                        reason));
    }
}
