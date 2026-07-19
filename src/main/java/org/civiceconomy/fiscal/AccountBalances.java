package org.civiceconomy.fiscal;

@FunctionalInterface
public interface AccountBalances {
    MoneyAmount balance(AccountId accountId);
}
