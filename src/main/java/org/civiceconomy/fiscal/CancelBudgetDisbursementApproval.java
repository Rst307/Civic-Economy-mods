package org.civiceconomy.fiscal;

import java.util.UUID;

public record CancelBudgetDisbursementApproval(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID approvalRequestId,
        UUID actorPlayerId,
        String reason) {
    public CancelBudgetDisbursementApproval {
        if (serviceIdentity == null || approvalRequestId == null || actorPlayerId == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval cancellation cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval cancellation values are invalid");
        }
    }
}
