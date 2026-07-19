package org.civiceconomy.fiscal;

public final class InsufficientAvailableBalanceException extends IllegalStateException {
    public InsufficientAvailableBalanceException(AccountId accountId, MoneyAmount requested, MoneyAmount available) {
        super("Account " + accountId.value() + " has " + available.minorUnits()
                + " available minor units but " + requested.minorUnits() + " were requested");
    }
}
