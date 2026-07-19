package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record BudgetDisbursementApproval(
        UUID approvalRequestId,
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID budgetId,
        AccountId recipientAccount,
        MoneyAmount amount,
        UUID actorPlayerId,
        String reason,
        UUID policyId,
        int requiredApprovals,
        List<BudgetDisbursementApprovalVote> votes,
        String state,
        Instant initiatedAt,
        Instant approvedAt,
        Instant executedAt,
        Instant expiresAt,
        Instant expiredAt,
        UUID cancelledByPlayerId,
        String cancellationReason,
        Instant cancelledAt) {
    public BudgetDisbursementApproval {
        if (approvalRequestId == null || serviceIdentity == null || nationId == null
                || budgetId == null || recipientAccount == null || amount == null
                || actorPlayerId == null || policyId == null || initiatedAt == null
                || expiresAt == null || requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank() || requiredApprovals < 1) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval cannot contain invalid values");
        }
        votes = List.copyOf(votes);
    }

    public List<UUID> approverPlayerIds() {
        return votes.stream().map(BudgetDisbursementApprovalVote::approverPlayerId).toList();
    }
}
