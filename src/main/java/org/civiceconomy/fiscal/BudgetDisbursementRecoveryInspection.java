package org.civiceconomy.fiscal;

import java.time.Clock;
import org.civiceconomy.persistence.CivicDatabase;

public final class BudgetDisbursementRecoveryInspection {
    private final CivicDatabase database;
    private final BudgetDisbursementApprovalRegistry approvals;

    public BudgetDisbursementRecoveryInspection(
            CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement recovery inspection dependencies cannot be null");
        }
        this.database = database;
        this.approvals = new BudgetDisbursementApprovalRegistry(database, clock);
    }

    public BudgetDisbursementRecoveryStatus status(
            ServiceIdentity serviceIdentity) {
        if (serviceIdentity == null) {
            throw new IllegalArgumentException(
                    "Budget Disbursement recovery Service Identity cannot be null");
        }
        return new BudgetDisbursementRecoveryStatus(
                approvals.approvedWithoutPayment(serviceIdentity),
                database.incompleteBudgetDisbursementPayments(
                                serviceIdentity.value())
                        .stream()
                        .map(PaymentCoordinator::toTransaction)
                        .toList());
    }
}
