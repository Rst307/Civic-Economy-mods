package org.civiceconomy.integration.lightmanscurrency;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

interface LightmansCurrencyPermanentDestructionAccounts {
    Object transactionLock();

    boolean wasApplied(UUID destructionId);

    MoneyAmount withdraw(AccountId accountId, MoneyAmount requested);

    void deposit(AccountId accountId, MoneyAmount amount);

    void recordApplied(UUID destructionId);
}
