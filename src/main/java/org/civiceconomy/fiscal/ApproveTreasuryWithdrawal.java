package org.civiceconomy.fiscal;

import java.util.UUID;

public record ApproveTreasuryWithdrawal(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID approvalRequestId,
        UUID approverPlayerId,
        String reason) {
    public ApproveTreasuryWithdrawal {
        if (serviceIdentity == null
                || approvalRequestId == null
                || approverPlayerId == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval reason cannot be blank");
        }
    }
}
