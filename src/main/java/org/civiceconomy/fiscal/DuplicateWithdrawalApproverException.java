package org.civiceconomy.fiscal;

import java.util.UUID;

public final class DuplicateWithdrawalApproverException extends IllegalStateException {
    public DuplicateWithdrawalApproverException(
            UUID approvalRequestId, UUID approverPlayerId) {
        super("Citizen " + approverPlayerId
                + " already approved Treasury Withdrawal " + approvalRequestId);
    }
}
