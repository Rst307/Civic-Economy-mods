package org.civiceconomy.integration.lightmanscurrency;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.ExternalPayments;
import org.civiceconomy.fiscal.MoneyAmount;

public final class LightmansCurrencyPlayerPayments implements ExternalPayments {
    private static final String PLAYER_ACCOUNT_PREFIX = "player:";

    private final PlayerBankAccounts accounts;

    LightmansCurrencyPlayerPayments(PlayerBankAccounts accounts) {
        this.accounts = accounts;
    }

    public static LightmansCurrencyPlayerPayments live() {
        return new LightmansCurrencyPlayerPayments(new LightmansCurrencyPlayerBankAccounts());
    }

    @Override
    public void apply(ExternalPayment payment) {
        UUID sourcePlayer = playerId(payment.sourceAccount());
        UUID recipientPlayer = playerId(payment.recipientAccount());
        synchronized (accounts.transactionLock()) {
            if (accounts.wasApplied(payment.transactionId())) {
                return;
            }

            MoneyAmount withdrawn = accounts.withdraw(sourcePlayer, payment.amount());
            if (!withdrawn.equals(payment.amount())) {
                if (!withdrawn.equals(MoneyAmount.ZERO)) {
                    accounts.deposit(sourcePlayer, withdrawn);
                }
                throw new InsufficientLightmansCurrencyBalanceException(
                        payment.sourceAccount(), payment.amount(), withdrawn);
            }

            try {
                accounts.deposit(recipientPlayer, withdrawn);
                accounts.recordApplied(payment.transactionId());
            } catch (RuntimeException failure) {
                accounts.deposit(sourcePlayer, withdrawn);
                throw failure;
            }
        }
    }

    private static UUID playerId(AccountId accountId) {
        String value = accountId.value();
        if (!value.startsWith(PLAYER_ACCOUNT_PREFIX)) {
            throw new IllegalArgumentException("Not an LC player account: " + value);
        }
        try {
            return UUID.fromString(value.substring(PLAYER_ACCOUNT_PREFIX.length()));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid LC player account: " + value, failure);
        }
    }
}
