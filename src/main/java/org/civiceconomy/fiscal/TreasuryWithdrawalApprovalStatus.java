package org.civiceconomy.fiscal;

public record TreasuryWithdrawalApprovalStatus(
        TreasuryWithdrawalApproval approval,
        boolean canApprove) {
    public TreasuryWithdrawalApprovalStatus {
        if (approval == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval status cannot be null");
        }
    }
}
