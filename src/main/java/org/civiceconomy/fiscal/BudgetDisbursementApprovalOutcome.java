package org.civiceconomy.fiscal;

import java.util.Optional;

public record BudgetDisbursementApprovalOutcome(
        BudgetDisbursementApproval approval, PaymentTransaction payment) {
    public BudgetDisbursementApprovalOutcome {
        if (approval == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement approval outcome requires an approval");
        }
    }

    public Optional<PaymentTransaction> paymentResult() {
        return Optional.ofNullable(payment);
    }
}
