package org.civiceconomy.persistence;

import java.util.List;
import java.util.UUID;

public record StoredTreasuryWithdrawalApproval(
        UUID approvalRequestId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        String sourceAccount,
        UUID actorPlayerId,
        long amountMinorUnits,
        String reason,
        UUID policyId,
        int requiredApprovals,
        List<StoredTreasuryWithdrawalApprovalVote> votes,
        String state,
        long initiatedAtEpochMillis,
        Long approvedAtEpochMillis,
        Long executedAtEpochMillis,
        long expiresAtEpochMillis,
        Long expiredAtEpochMillis,
        UUID cancelledByPlayerId,
        String cancellationReason,
        Long cancelledAtEpochMillis) {
    public StoredTreasuryWithdrawalApproval {
        votes = List.copyOf(votes);
    }
}
