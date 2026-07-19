package org.civiceconomy.fiscal;

import java.util.List;

public record TreasuryWithdrawalRecoveryStatus(
        List<TreasuryWithdrawalApproval> approvedWithoutOperation,
        List<TreasuryWithdrawal> preparedOperations) {
    public TreasuryWithdrawalRecoveryStatus {
        approvedWithoutOperation = List.copyOf(approvedWithoutOperation);
        preparedOperations = List.copyOf(preparedOperations);
    }
}
