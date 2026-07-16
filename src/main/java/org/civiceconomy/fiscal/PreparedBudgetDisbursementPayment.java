package org.civiceconomy.fiscal;

import java.util.UUID;

public record PreparedBudgetDisbursementPayment(
        UUID approvalRequestId,
        PaymentTransaction transaction,
        FiscalServiceSession session) {
    public PreparedBudgetDisbursementPayment {
        if (approvalRequestId == null || transaction == null || session == null) {
            throw new IllegalArgumentException(
                    "Prepared Budget Disbursement payment cannot contain null values");
        }
    }
}
