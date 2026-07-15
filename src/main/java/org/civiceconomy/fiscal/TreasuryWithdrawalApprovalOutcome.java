package org.civiceconomy.fiscal;

public record TreasuryWithdrawalApprovalOutcome(
        TreasuryWithdrawalApproval approval,
        TreasuryWithdrawal withdrawal) {
    public TreasuryWithdrawalApprovalOutcome {
        if (approval == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal approval outcome cannot be null");
        }
    }

    public boolean executed() {
        return withdrawal != null && withdrawal.state().equals("COMMITTED");
    }
}
