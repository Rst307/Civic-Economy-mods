package org.civiceconomy.fiscal;

import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredBudget;

public final class NationBudgetInspection {
    private final CivicDatabase database;
    private final NationProvider nations;
    private final NationFiscalAuthorityRegistry authorities;

    public NationBudgetInspection(
            CivicDatabase database,
            NationProvider nations,
            NationFiscalAuthorityRegistry authorities) {
        if (database == null || nations == null || authorities == null) {
            throw new IllegalArgumentException(
                    "Nation Budget inspection dependencies cannot be null");
        }
        this.database = database;
        this.nations = nations;
        this.authorities = authorities;
    }

    public List<Budget> list(UUID actorPlayerId) {
        return database.budgetsForSourceAccount(
                        treasuryAccount(authorizedNation(actorPlayerId))).stream()
                .map(FiscalLedger::toBudget)
                .toList();
    }

    public Budget status(UUID actorPlayerId, UUID budgetId) {
        if (budgetId == null) {
            throw new IllegalArgumentException("Budget ID cannot be null");
        }
        StoredBudget budget = database.budgetForSourceAccount(
                budgetId, treasuryAccount(authorizedNation(actorPlayerId)));
        if (budget == null) {
            throw new SecurityException("Budget is not visible to this Nation");
        }
        return FiscalLedger.toBudget(budget);
    }

    private NationId authorizedNation(UUID actorPlayerId) {
        if (actorPlayerId == null) {
            throw new IllegalArgumentException("Budget inspection actor cannot be null");
        }
        NationId nationId = nations.findForCitizen(actorPlayerId)
                .orElseThrow(() -> new SecurityException(
                        "Budget Nation inspection requires effective Citizenship"))
                .nationId();
        authorities.require(
                nationId, actorPlayerId, NationFiscalPermission.VIEW_ACCOUNT);
        return nationId;
    }

    private static String treasuryAccount(NationId nationId) {
        return "nation:" + nationId.value() + ":treasury";
    }
}
