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
import org.civiceconomy.fiscal.MoneyAmount;

final class LightmansCurrencyPlayerBankAccounts implements PlayerBankAccounts {
    @Override
    public Object transactionLock() {
        return bankData();
    }

    @Override
    public boolean wasApplied(UUID transactionId) {
        return transactions().civicEconomy$wasApplied(transactionId);
    }

    @Override
    public MoneyAmount withdraw(UUID playerId, MoneyAmount requested) {
        Pair<Boolean, MoneyValue> result = BankAPI.getApi().BankWithdrawFromServer(
                account(playerId), value(requested));
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
    public void deposit(UUID playerId, MoneyAmount amount) {
        if (!BankAPI.getApi().BankDepositFromServer(account(playerId), value(amount))) {
            throw new IllegalStateException("LC rejected a server bank deposit for player " + playerId);
        }
    }

    @Override
    public void recordApplied(UUID transactionId) {
        transactions().civicEconomy$recordApplied(transactionId);
    }

    private static IBankAccount account(UUID playerId) {
        return bankData().getAccount(playerId);
    }

    private static MoneyValue value(MoneyAmount amount) {
        MoneyValue value = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, amount.minorUnits());
        if (value.isEmpty() && !amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalStateException("LC main coin chain is unavailable for " + amount.minorUnits()
                    + " minor units");
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
