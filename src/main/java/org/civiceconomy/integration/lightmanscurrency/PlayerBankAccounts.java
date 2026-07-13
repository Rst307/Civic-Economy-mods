package org.civiceconomy.integration.lightmanscurrency;

import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;

interface PlayerBankAccounts {
    Object transactionLock();

    boolean wasApplied(UUID transactionId);

    MoneyAmount withdraw(UUID playerId, MoneyAmount requested);

    void deposit(UUID playerId, MoneyAmount amount);

    void recordApplied(UUID transactionId);
}
