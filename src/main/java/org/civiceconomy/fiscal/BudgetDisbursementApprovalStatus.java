package org.civiceconomy.fiscal;

public record BudgetDisbursementApprovalStatus(
        BudgetDisbursementApproval approval, boolean canApprove) {
    public BudgetDisbursementApprovalStatus {
        if (approval == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval status cannot be null");
        }
    }
}
