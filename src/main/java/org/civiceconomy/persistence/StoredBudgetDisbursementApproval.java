package org.civiceconomy.persistence;

import java.util.List;
import java.util.UUID;

public record StoredBudgetDisbursementApproval(
        UUID approvalRequestId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID budgetId,
        String recipientAccount,
        long amountMinorUnits,
        UUID actorPlayerId,
        String reason,
        UUID policyId,
        int requiredApprovals,
        List<StoredBudgetDisbursementApprovalVote> votes,
        String state,
        long initiatedAtEpochMillis,
        Long approvedAtEpochMillis,
        Long executedAtEpochMillis,
        long expiresAtEpochMillis,
        Long expiredAtEpochMillis,
        UUID cancelledByPlayerId,
        String cancellationReason,
        Long cancelledAtEpochMillis) {}
