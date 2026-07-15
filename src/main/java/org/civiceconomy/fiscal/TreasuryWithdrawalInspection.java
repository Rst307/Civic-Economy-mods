package org.civiceconomy.fiscal;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;

public final class TreasuryWithdrawalInspection {
    private final NationProvider nations;
    private final NationFiscalAuthorityRegistry authorities;
    private final WithdrawalApprovalPolicyRegistry policies;
    private final TreasuryWithdrawalApprovalRegistry approvals;
    private final Clock clock;

    public TreasuryWithdrawalInspection(
            NationProvider nations,
            NationFiscalAuthorityRegistry authorities,
            WithdrawalApprovalPolicyRegistry policies,
            TreasuryWithdrawalApprovalRegistry approvals,
            Clock clock) {
        if (nations == null
                || authorities == null
                || policies == null
                || approvals == null
                || clock == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal inspection dependencies cannot be null");
        }
        this.nations = nations;
        this.authorities = authorities;
        this.policies = policies;
        this.approvals = approvals;
        this.clock = clock;
    }

    public WithdrawalApprovalPolicyVersion currentPolicy(UUID actorPlayerId) {
        if (actorPlayerId == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal inspection actor cannot be null");
        }
        return policies.current(nationForCitizen(actorPlayerId), clock.instant());
    }

    public List<TreasuryWithdrawalApprovalStatus> approvalStatuses(UUID actorPlayerId) {
        NationId nationId = authorizedApprovalNation(actorPlayerId);
        return approvals.listForNation(nationId).stream()
                .map(approval -> status(approval, actorPlayerId))
                .toList();
    }

    public TreasuryWithdrawalApprovalStatus approvalStatus(
            UUID actorPlayerId, UUID approvalRequestId) {
        if (approvalRequestId == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval request ID cannot be null");
        }
        NationId nationId = authorizedApprovalNation(actorPlayerId);
        return status(
                approvals.findForNation(approvalRequestId, nationId), actorPlayerId);
    }

    private NationId authorizedApprovalNation(UUID actorPlayerId) {
        NationId nationId = nationForCitizen(actorPlayerId);
        authorities.require(
                nationId, actorPlayerId, NationFiscalPermission.MANAGE_WITHDRAWAL);
        return nationId;
    }

    private NationId nationForCitizen(UUID actorPlayerId) {
        if (actorPlayerId == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal inspection actor cannot be null");
        }
        return nations.findForCitizen(actorPlayerId)
                .orElseThrow(() -> new SecurityException(
                        "Treasury Withdrawal inspection requires effective Citizenship"))
                .nationId();
    }

    private static TreasuryWithdrawalApprovalStatus status(
            TreasuryWithdrawalApproval approval, UUID actorPlayerId) {
        boolean canApprove = approval.state().equals("PENDING")
                && approval.votes().stream().noneMatch(
                        vote -> vote.approverPlayerId().equals(actorPlayerId));
        return new TreasuryWithdrawalApprovalStatus(approval, canApprove);
    }
}
