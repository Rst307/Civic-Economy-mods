package org.civiceconomy.fiscal;

import java.util.UUID;

public final class DuplicateBudgetDisbursementApproverException
        extends IllegalStateException {
    public DuplicateBudgetDisbursementApproverException(
            UUID approvalRequestId, UUID approverPlayerId) {
        super("Citizen " + approverPlayerId
                + " already approved Budget Disbursement " + approvalRequestId);
    }
}
