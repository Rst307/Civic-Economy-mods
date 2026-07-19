package org.civiceconomy.fiscal;

import java.util.UUID;

public record ApproveBudgetDisbursementApproval(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID approvalRequestId,
        UUID approverPlayerId,
        String reason) {
    public ApproveBudgetDisbursementApproval {
        if (serviceIdentity == null || approvalRequestId == null || approverPlayerId == null
                || requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval vote is invalid");
        }
    }
}
