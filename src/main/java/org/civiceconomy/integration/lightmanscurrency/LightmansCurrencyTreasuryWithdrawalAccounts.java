package org.civiceconomy.integration.lightmanscurrency;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

interface LightmansCurrencyTreasuryWithdrawalAccounts {
    Object transactionLock();

    boolean wasTreasuryDebitApplied(UUID withdrawalId);

    boolean wasCashDelivered(UUID withdrawalId, UUID playerId);

    void requireCashCapacity(UUID playerId, MoneyAmount amount);

    void debitTreasury(UUID withdrawalId, AccountId sourceAccount, MoneyAmount amount);

    void deliverCash(UUID withdrawalId, UUID playerId, MoneyAmount amount);
}
