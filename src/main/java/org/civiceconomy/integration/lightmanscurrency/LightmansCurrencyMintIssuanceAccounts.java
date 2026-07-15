package org.civiceconomy.integration.lightmanscurrency;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

interface LightmansCurrencyMintIssuanceAccounts {
    Object transactionLock();

    boolean wasApplied(UUID issuanceId);

    void deposit(AccountId accountId, MoneyAmount amount);

    MoneyAmount withdraw(AccountId accountId, MoneyAmount amount);

    void recordApplied(UUID issuanceId);
}
