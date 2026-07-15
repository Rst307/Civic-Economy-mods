package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.UUID;

public record TreasuryWithdrawalApprovalVote(
        UUID voteId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID approverPlayerId,
        String reason,
        Instant approvedAt) {
    public TreasuryWithdrawalApprovalVote {
        if (voteId == null
                || serviceIdentity == null
                || approverPlayerId == null
                || approvedAt == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval vote cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval vote values are invalid");
        }
    }
}
