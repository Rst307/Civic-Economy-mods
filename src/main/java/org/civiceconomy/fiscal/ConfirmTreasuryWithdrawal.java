package org.civiceconomy.fiscal;

import java.util.UUID;
import org.civiceconomy.nation.NationId;

public record ConfirmTreasuryWithdrawal(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationId nationId,
        AccountId sourceAccount,
        UUID actorPlayerId,
        MoneyAmount amount,
        String reason) {
    public ConfirmTreasuryWithdrawal {
        if (serviceIdentity == null
                || nationId == null
                || sourceAccount == null
                || actorPlayerId == null
                || amount == null) {
            throw new IllegalArgumentException("Treasury Withdrawal cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || reason == null
                || reason.isBlank()
                || amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Treasury Withdrawal values are invalid");
        }
        AccountId expectedTreasury =
                new AccountId("nation:" + nationId.value() + ":treasury");
        if (!sourceAccount.equals(expectedTreasury)) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal source must be the Nation's National Treasury");
        }
    }
}
