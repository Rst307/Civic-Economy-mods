package org.civiceconomy.fiscal;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.persistence.CivicDatabase;

public final class NationBudgetApprovalCoordinator {
    private final CivicDatabase database;
    private final NationProvider nations;
    private final NationFiscalAuthorityRegistry authorities;
    private final Clock clock;
    private final Function<FiscalAuthorization, FiscalServiceSession> sessionFactory;

    public NationBudgetApprovalCoordinator(
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

    NationBudgetApprovalCoordinator(
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
                    "Nation Budget approval dependencies cannot be null");
        }
        this.database = database;
        this.nations = nations;
        this.authorities = authorities;
        this.clock = clock;
        this.sessionFactory = sessionFactory;
    }

    public Budget approve(
            UUID actorPlayerId,
            UUID budgetId,
            String requestId,
            String reason,
            AccountBalances accountBalances) {
        if (actorPlayerId == null || budgetId == null || accountBalances == null) {
            throw new IllegalArgumentException("Budget approval identity cannot be null");
        }
        NationId nationId = nations.findForCitizen(actorPlayerId)
                .orElseThrow(() -> new SecurityException(
                        "Budget approval requires effective Citizenship"))
                .nationId();
        authorities.require(
                nationId, actorPlayerId, NationFiscalPermission.APPROVE_BUDGET);
        AccountId treasury = new AccountId("nation:" + nationId.value() + ":treasury");
        if (database.budgetForSourceAccount(budgetId, treasury.value()) == null) {
            throw new SecurityException("Budget is not approvable by this Nation");
        }

        FiscalAuthorization authorization = new FiscalAuthorization(database);
        new BudgetFiscalServiceProvisioner(authorization).ensureAuthorized(treasury);
        FiscalServiceSession session = sessionFactory.apply(authorization);
        return FiscalLedger.authorized(database, accountBalances, clock, session)
                .approveBudget(new ApproveBudget(
                        BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                        requestId,
                        budgetId,
                        actorPlayerId,
                        reason));
    }
}
