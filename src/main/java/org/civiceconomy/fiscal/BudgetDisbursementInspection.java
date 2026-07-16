package org.civiceconomy.fiscal;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;

public final class BudgetDisbursementInspection {
    private final NationProvider nations;
    private final NationFiscalAuthorityRegistry authorities;
    private final BudgetDisbursementApprovalPolicyRegistry policies;
    private final BudgetDisbursementApprovalRegistry approvals;
    private final Clock clock;

    public BudgetDisbursementInspection(
            NationProvider nations,
            NationFiscalAuthorityRegistry authorities,
            BudgetDisbursementApprovalPolicyRegistry policies,
            BudgetDisbursementApprovalRegistry approvals,
            Clock clock) {
        if (nations == null || authorities == null || policies == null
                || approvals == null || clock == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement inspection dependencies cannot be null");
        }
        this.nations = nations;
        this.authorities = authorities;
        this.policies = policies;
        this.approvals = approvals;
        this.clock = clock;
    }

    public BudgetDisbursementApprovalPolicyVersion currentPolicy(UUID actorPlayerId) {
        return policies.current(nationForCitizen(actorPlayerId), clock.instant());
    }

    public List<BudgetDisbursementApprovalPolicyVersion> policyHistory(UUID actorPlayerId) {
        return policies.history(nationForCitizen(actorPlayerId));
    }

    public List<BudgetDisbursementApprovalStatus> approvalStatuses(UUID actorPlayerId) {
        NationId nationId = authorizedApprovalNation(actorPlayerId);
        return approvals.listForNation(nationId).stream()
                .map(approval -> status(approval, actorPlayerId))
                .toList();
    }

    public BudgetDisbursementApprovalStatus approvalStatus(
            UUID actorPlayerId, UUID approvalRequestId) {
        if (approvalRequestId == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval request ID cannot be null");
        }
        NationId nationId = authorizedApprovalNation(actorPlayerId);
        return status(
                approvals.findForNation(approvalRequestId, nationId), actorPlayerId);
    }

    private NationId authorizedApprovalNation(UUID actorPlayerId) {
        NationId nationId = nationForCitizen(actorPlayerId);
        authorities.require(
                nationId, actorPlayerId, NationFiscalPermission.APPROVE_PAYMENT);
        return nationId;
    }

    private NationId nationForCitizen(UUID actorPlayerId) {
        if (actorPlayerId == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement inspection actor cannot be null");
        }
        return nations.findForCitizen(actorPlayerId)
                .orElseThrow(() -> new SecurityException(
                        "Budget Disbursement inspection requires effective Citizenship"))
                .nationId();
    }

    private BudgetDisbursementApprovalStatus status(
            BudgetDisbursementApproval approval, UUID actorPlayerId) {
        boolean canApprove = approval.state().equals("PENDING")
                && clock.instant().isBefore(approval.expiresAt())
                && approval.votes().stream().noneMatch(
                        vote -> vote.approverPlayerId().equals(actorPlayerId));
        return new BudgetDisbursementApprovalStatus(approval, canApprove);
    }
}
