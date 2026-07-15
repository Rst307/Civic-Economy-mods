package org.civiceconomy.monetary;

import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ConfirmPermanentDestruction(
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId sourceAccount,
        MoneyAmount amount,
        String operatorIdentity,
        String reason) {
    public ConfirmPermanentDestruction(
            ServiceIdentity serviceIdentity,
            String requestId,
            AccountId sourceAccount,
            MoneyAmount amount,
            String reason) {
        this(
                serviceIdentity,
                requestId,
                sourceAccount,
                amount,
                serviceIdentity == null ? null : "service:" + serviceIdentity.value(),
                reason);
    }

    public ConfirmPermanentDestruction {
        if (serviceIdentity == null || sourceAccount == null || amount == null) {
            throw new IllegalArgumentException("Permanent Destruction request cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || operatorIdentity == null
                || operatorIdentity.isBlank()
                || reason == null
                || reason.isBlank()
                || amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Permanent Destruction request values are invalid");
        }
    }
}
