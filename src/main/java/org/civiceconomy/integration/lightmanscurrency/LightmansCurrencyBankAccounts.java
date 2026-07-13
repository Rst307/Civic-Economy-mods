package org.civiceconomy.integration.lightmanscurrency;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

interface LightmansCurrencyBankAccounts {
    Object transactionLock();

    boolean wasApplied(UUID transactionId);

    MoneyAmount withdraw(AccountId accountId, MoneyAmount requested);

    void deposit(AccountId accountId, MoneyAmount amount);

    void recordApplied(UUID transactionId);
}
