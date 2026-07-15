package org.civiceconomy.integration.lightmanscurrency;

import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;

public final class InsufficientTreasuryWithdrawalInventoryCapacityException
        extends IllegalStateException {
    public InsufficientTreasuryWithdrawalInventoryCapacityException(
            UUID playerId, MoneyAmount amount) {
        super("Player " + playerId + " cannot hold Treasury Withdrawal cash " + amount);
    }
}
