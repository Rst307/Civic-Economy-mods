package org.civiceconomy.fiscal;

import java.time.Clock;
import java.util.UUID;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.persistence.CivicDatabase;

public final class NationBudgetDisbursementApprovalCoordinator {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-budget-disbursement");

    private final CivicDatabase database;
    private final NationProvider nations;
    private final NationFiscalAuthorityRegistry authorities;
    private final Clock clock;

    public NationBudgetDisbursementApprovalCoordinator(
            CivicDatabase database,
            NationProvider nations,
            NationFiscalAuthorityRegistry authorities,
            Clock clock) {
        if (database == null || nations == null || authorities == null || clock == null) {
            throw new IllegalArgumentException(
                    "Nation Budget Disbursement approval dependencies cannot be null");
        }
        this.database = database;
        this.nations = nations;
        this.authorities = authorities;
        this.clock = clock;
    }

    public BudgetDisbursementApproval initiate(
            UUID actorPlayerId,
            UUID budgetId,
            AccountId recipientAccount,
            MoneyAmount amount,
            String requestId,
            String reason) {
        if (actorPlayerId == null || budgetId == null
                || recipientAccount == null || amount == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval identity cannot be null");
        }
        var nationId = nations.findForCitizen(actorPlayerId)
                .orElseThrow(() -> new SecurityException(
                        "Budget Disbursement requires effective Citizenship"))
                .nationId();
        authorities.require(
                nationId, actorPlayerId, NationFiscalPermission.INITIATE_PAYMENT);
        String treasury = "nation:" + nationId.value() + ":treasury";
        if (database.budgetForSourceAccount(budgetId, treasury) == null) {
            throw new SecurityException(
                    "Budget is not available for this Nation's Disbursement approval");
        }
        return new BudgetDisbursementApprovalRegistry(database, clock)
                .initiate(new InitiateBudgetDisbursementApproval(
                        SERVICE_IDENTITY,
                        requestId,
                        nationId,
                        budgetId,
                        recipientAccount,
                        amount,
                        actorPlayerId,
                        reason));
    }

    public BudgetDisbursementApproval approve(
            UUID actorPlayerId,
            UUID approvalRequestId,
            String requestId,
            String reason) {
        if (actorPlayerId == null || approvalRequestId == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval vote identity cannot be null");
        }
        var nationId = nations.findForCitizen(actorPlayerId)
                .orElseThrow(() -> new SecurityException(
                        "Budget Disbursement approval requires effective Citizenship"))
                .nationId();
        authorities.require(
                nationId, actorPlayerId, NationFiscalPermission.APPROVE_PAYMENT);
        BudgetDisbursementApprovalRegistry approvals =
                new BudgetDisbursementApprovalRegistry(database, clock);
        BudgetDisbursementApproval existing = approvals.find(approvalRequestId);
        if (!existing.nationId().equals(nationId)) {
            throw new SecurityException(
                    "Budget Disbursement approval belongs to another Nation");
        }
        return approvals.approve(new ApproveBudgetDisbursementApproval(
                SERVICE_IDENTITY,
                requestId,
                approvalRequestId,
                actorPlayerId,
                reason));
    }
}
