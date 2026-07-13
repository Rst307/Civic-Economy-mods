package org.civiceconomy.integration.lightmanscurrency;

import com.mojang.datafixers.util.Pair;
import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.data.CustomSaveData;
import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

final class LiveLightmansCurrencyBankAccounts implements LightmansCurrencyBankAccounts {
    private static final String PLAYER_ACCOUNT_PREFIX = "player:";

    private final LightmansCurrencyFiscalAccounts fiscalAccounts;

    LiveLightmansCurrencyBankAccounts(LightmansCurrencyFiscalAccounts fiscalAccounts) {
        this.fiscalAccounts = fiscalAccounts;
    }

    @Override
    public Object transactionLock() {
        return bankData();
    }

    @Override
    public boolean wasApplied(UUID transactionId) {
        return transactions().civicEconomy$wasApplied(transactionId);
    }

    @Override
    public MoneyAmount withdraw(AccountId accountId, MoneyAmount requested) {
        Pair<Boolean, MoneyValue> result =
                BankAPI.getApi().BankWithdrawFromServer(account(accountId), value(requested));
        if (!result.getFirst()) {
            return MoneyAmount.ZERO;
        }
        long withdrawn = result.getSecond().getCoreValue();
        if (withdrawn < 0) {
            throw new IllegalStateException("LC returned a negative withdrawn core value: " + withdrawn);
        }
        return MoneyAmount.ofMinorUnits(withdrawn);
    }

    @Override
    public void deposit(AccountId accountId, MoneyAmount amount) {
        if (!BankAPI.getApi().BankDepositFromServer(account(accountId), value(amount))) {
            throw new IllegalStateException("LC rejected a server bank deposit for " + accountId.value());
        }
    }

    @Override
    public void recordApplied(UUID transactionId) {
        transactions().civicEconomy$recordApplied(transactionId);
    }

    private IBankAccount account(AccountId accountId) {
        String value = accountId.value();
        if (value.startsWith(PLAYER_ACCOUNT_PREFIX)) {
            return bankData().getAccount(playerId(accountId));
        }
        return fiscalAccounts.requireAccount(accountId);
    }

    private static UUID playerId(AccountId accountId) {
        String value = accountId.value();
        try {
            return UUID.fromString(value.substring(PLAYER_ACCOUNT_PREFIX.length()));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid LC player account: " + value, failure);
        }
    }

    private static MoneyValue value(MoneyAmount amount) {
        MoneyValue value = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, amount.minorUnits());
        if (value.isEmpty() && !amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalStateException(
                    "LC main coin chain is unavailable for " + amount.minorUnits() + " minor units");
        }
        return value;
    }

    private static CivicBankDataTransactions transactions() {
        BankDataCache bankData = bankData();
        if (!(bankData instanceof CivicBankDataTransactions transactions)) {
            throw new IllegalStateException("Civic LC transaction Mixin was not applied");
        }
        return transactions;
    }

    private static BankDataCache bankData() {
        return CustomSaveData.getData(BankDataCache.TYPE);
    }
}
