package org.civiceconomy.fiscal;

import java.util.UUID;

public record ExternalTreasuryWithdrawal(
        UUID withdrawalId,
        AccountId sourceAccount,
        UUID actorPlayerId,
        MoneyAmount amount) {
    public ExternalTreasuryWithdrawal {
        if (withdrawalId == null
                || sourceAccount == null
                || actorPlayerId == null
                || amount == null
                || amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("External Treasury Withdrawal values are invalid");
        }
    }
}
