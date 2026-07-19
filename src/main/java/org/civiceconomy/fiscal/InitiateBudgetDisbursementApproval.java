package org.civiceconomy.fiscal;

import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record InitiateBudgetDisbursementApproval(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        UUID budgetId,
        AccountId recipientAccount,
        MoneyAmount amount,
        UUID actorPlayerId,
        String reason) {
    public InitiateBudgetDisbursementApproval {
        if (serviceIdentity == null || nationId == null || budgetId == null
                || recipientAccount == null || amount == null || actorPlayerId == null) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank() || reason == null || reason.isBlank()
                || amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException(
                    "Budget disbursement approval request is invalid");
        }
    }
}
