package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.UUID;

public record BudgetDisbursementApprovalVote(
        UUID voteId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID approverPlayerId,
        String reason,
        Instant approvedAt) {
    public BudgetDisbursementApprovalVote {
        if (voteId == null || serviceIdentity == null || approverPlayerId == null
                || approvedAt == null || requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval vote is invalid");
        }
    }
}
