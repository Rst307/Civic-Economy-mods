package org.civiceconomy.monetary;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

public record ExternalPermanentDestruction(
        UUID destructionId, AccountId sourceAccount, MoneyAmount amount) {
    public ExternalPermanentDestruction {
        if (destructionId == null || sourceAccount == null || amount == null) {
            throw new IllegalArgumentException("Permanent Destruction cannot contain null values");
        }
        if (amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Permanent Destruction amount must be positive");
        }
    }
}
