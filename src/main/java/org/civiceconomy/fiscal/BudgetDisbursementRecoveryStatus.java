package org.civiceconomy.fiscal;

import java.util.List;

public record BudgetDisbursementRecoveryStatus(
        List<BudgetDisbursementApproval> approvedWithoutPayment,
        List<PaymentTransaction> incompletePayments) {
    public BudgetDisbursementRecoveryStatus {
        if (approvedWithoutPayment == null || incompletePayments == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement recovery status cannot contain null lists");
        }
        approvedWithoutPayment = List.copyOf(approvedWithoutPayment);
        incompletePayments = List.copyOf(incompletePayments);
    }
}
