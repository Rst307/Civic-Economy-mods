package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record TreasuryWithdrawalApproval(
        UUID approvalRequestId,
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        AccountId sourceAccount,
        UUID actorPlayerId,
        MoneyAmount amount,
        String reason,
        UUID policyId,
        int requiredApprovals,
        List<TreasuryWithdrawalApprovalVote> votes,
        String state,
        Instant initiatedAt,
        Instant approvedAt,
        Instant executedAt) {
    public TreasuryWithdrawalApproval {
        if (approvalRequestId == null
                || serviceIdentity == null
                || nationId == null
                || sourceAccount == null
                || actorPlayerId == null
                || amount == null
                || policyId == null
                || initiatedAt == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank()
                || requiredApprovals < 1) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval values are invalid");
        }
        votes = List.copyOf(votes);
    }

    public List<UUID> approverPlayerIds() {
        return votes.stream().map(TreasuryWithdrawalApprovalVote::approverPlayerId).toList();
    }

    public ConfirmTreasuryWithdrawal withdrawal() {
        return new ConfirmTreasuryWithdrawal(
                serviceIdentity,
                requestId,
                nationId,
                sourceAccount,
                actorPlayerId,
                amount,
                reason);
    }
}
