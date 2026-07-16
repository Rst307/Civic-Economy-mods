package org.civiceconomy.fiscal;

public record BudgetDisbursementApprovalTier(
        MoneyAmount minimumAmount, int requiredApprovals) {
    public BudgetDisbursementApprovalTier {
        if (minimumAmount == null || requiredApprovals < 1 || requiredApprovals > 16) {
            throw new IllegalArgumentException("Budget disbursement approval tier is invalid");
        }
    }
}
