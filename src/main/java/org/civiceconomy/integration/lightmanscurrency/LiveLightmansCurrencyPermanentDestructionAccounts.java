package org.civiceconomy.integration.lightmanscurrency;

import com.mojang.datafixers.util.Pair;
import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

final class LiveLightmansCurrencyPermanentDestructionAccounts
        implements LightmansCurrencyPermanentDestructionAccounts {
    private final LightmansCurrencyFiscalAccounts fiscalAccounts;

    LiveLightmansCurrencyPermanentDestructionAccounts(
            LightmansCurrencyFiscalAccounts fiscalAccounts) {
        this.fiscalAccounts = fiscalAccounts;
    }

    @Override
    public Object transactionLock() {
        return fiscalAccounts.data();
    }

    @Override
    public boolean wasApplied(UUID destructionId) {
        return fiscalAccounts.wasPermanentDestructionApplied(destructionId);
    }

    @Override
    public MoneyAmount withdraw(AccountId accountId, MoneyAmount requested) {
        Pair<Boolean, MoneyValue> result = BankAPI.getApi()
                .BankWithdrawFromServer(fiscalAccounts.requireAccount(accountId), value(requested));
        if (!result.getFirst()) {
            return MoneyAmount.ZERO;
        }
        long withdrawn = result.getSecond().getCoreValue();
        if (withdrawn < 0L) {
            throw new IllegalStateException("LC returned a negative withdrawn core value: " + withdrawn);
        }
        return MoneyAmount.ofMinorUnits(withdrawn);
    }

    @Override
    public void deposit(AccountId accountId, MoneyAmount amount) {
        if (!BankAPI.getApi().BankDepositFromServer(
                fiscalAccounts.requireAccount(accountId), value(amount))) {
            throw new IllegalStateException(
                    "LC rejected a server bank deposit for " + accountId.value());
        }
    }

    @Override
    public void recordApplied(UUID destructionId) {
        fiscalAccounts.recordPermanentDestructionApplied(destructionId);
    }

    private static MoneyValue value(MoneyAmount amount) {
        MoneyValue value = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, amount.minorUnits());
        if (value.isEmpty() && !amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalStateException(
                    "LC main coin chain is unavailable for "
                            + amount.minorUnits()
                            + " minor units");
        }
        return value;
    }
}
