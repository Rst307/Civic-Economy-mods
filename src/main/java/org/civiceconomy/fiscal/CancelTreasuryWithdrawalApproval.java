package org.civiceconomy.fiscal;

import java.util.UUID;

public record CancelTreasuryWithdrawalApproval(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID approvalRequestId,
        UUID actorPlayerId,
        String reason) {
    public CancelTreasuryWithdrawalApproval {
        if (serviceIdentity == null || approvalRequestId == null || actorPlayerId == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval cancellation cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval cancellation values are invalid");
        }
    }
}
