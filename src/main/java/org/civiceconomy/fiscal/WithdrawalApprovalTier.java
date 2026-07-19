package org.civiceconomy.fiscal;

public record WithdrawalApprovalTier(
        MoneyAmount minimumAmount,
        int requiredApprovals) {
    public WithdrawalApprovalTier {
        if (minimumAmount == null) {
            throw new IllegalArgumentException("Withdrawal approval minimum amount cannot be null");
        }
        if (requiredApprovals < 1 || requiredApprovals > 16) {
            throw new IllegalArgumentException(
                    "Withdrawal approval count must be between 1 and 16");
        }
    }
}
