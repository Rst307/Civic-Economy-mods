package org.civiceconomy.integration.lightmanscurrency;

import net.minecraft.server.level.ServerLevel;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.ExternalPayments;
import org.civiceconomy.fiscal.MoneyAmount;

public final class LightmansCurrencyPayments implements ExternalPayments {
    private final LightmansCurrencyBankAccounts accounts;

    LightmansCurrencyPayments(LightmansCurrencyBankAccounts accounts) {
        this.accounts = accounts;
    }

    public static LightmansCurrencyPayments live() {
        return new LightmansCurrencyPayments(
                new LiveLightmansCurrencyBankAccounts(LightmansCurrencyFiscalAccounts.live()));
    }

    public static LightmansCurrencyPayments live(ServerLevel level) {
        return new LightmansCurrencyPayments(
                new LiveLightmansCurrencyBankAccounts(LightmansCurrencyFiscalAccounts.forLevel(level)));
    }

    @Override
    public void apply(ExternalPayment payment) {
        synchronized (accounts.transactionLock()) {
            if (accounts.wasApplied(payment.transactionId())) {
                return;
            }

            MoneyAmount withdrawn = accounts.withdraw(payment.sourceAccount(), payment.amount());
            if (!withdrawn.equals(payment.amount())) {
                if (!withdrawn.equals(MoneyAmount.ZERO)) {
                    accounts.deposit(payment.sourceAccount(), withdrawn);
                }
                throw new InsufficientLightmansCurrencyBalanceException(
                        payment.sourceAccount(), payment.amount(), withdrawn);
            }

            try {
                accounts.deposit(payment.recipientAccount(), withdrawn);
                accounts.recordApplied(payment.transactionId());
            } catch (RuntimeException failure) {
                accounts.deposit(payment.sourceAccount(), withdrawn);
                throw failure;
            }
        }
    }
}
