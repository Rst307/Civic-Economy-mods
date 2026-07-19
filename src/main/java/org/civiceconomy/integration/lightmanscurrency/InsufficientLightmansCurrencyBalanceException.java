package org.civiceconomy.integration.lightmanscurrency;

import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

public final class InsufficientLightmansCurrencyBalanceException extends IllegalStateException {
    public InsufficientLightmansCurrencyBalanceException(
            AccountId sourceAccount, MoneyAmount requested, MoneyAmount withdrawn) {
        super("LC account " + sourceAccount.value() + " could withdraw only " + withdrawn.minorUnits()
                + " of " + requested.minorUnits() + " requested minor units");
    }
}
