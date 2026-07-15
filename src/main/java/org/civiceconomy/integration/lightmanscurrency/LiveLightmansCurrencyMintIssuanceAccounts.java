package org.civiceconomy.integration.lightmanscurrency;

import com.mojang.datafixers.util.Pair;
import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

final class LiveLightmansCurrencyMintIssuanceAccounts
        implements LightmansCurrencyMintIssuanceAccounts {
    private final LightmansCurrencyFiscalAccounts accounts;

    LiveLightmansCurrencyMintIssuanceAccounts(LightmansCurrencyFiscalAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    public Object transactionLock() {
        return accounts.transactionLock();
    }

    @Override
    public boolean wasApplied(UUID issuanceId) {
        return accounts.wasMintIssuanceApplied(issuanceId);
    }

    @Override
    public void deposit(AccountId accountId, MoneyAmount amount) {
        if (!BankAPI.getApi().BankDepositFromServer(accounts.requireAccount(accountId), value(amount))) {
            throw new IllegalStateException(
                    "LC rejected a Mint issuance deposit for " + accountId.value());
        }
    }

    @Override
    public MoneyAmount withdraw(AccountId accountId, MoneyAmount amount) {
        Pair<Boolean, MoneyValue> result = BankAPI.getApi()
                .BankWithdrawFromServer(accounts.requireAccount(accountId), value(amount));
        if (!result.getFirst()) {
            return MoneyAmount.ZERO;
        }
        return MoneyAmount.ofMinorUnits(result.getSecond().getCoreValue());
    }

    @Override
    public void recordApplied(UUID issuanceId) {
        accounts.recordMintIssuanceApplied(issuanceId);
    }

    private static MoneyValue value(MoneyAmount amount) {
        MoneyValue value = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, amount.minorUnits());
        if (value.isEmpty() && !amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalStateException(
                    "LC main coin chain is unavailable for "
                            + amount.minorUnits() + " Mint minor units");
        }
        return value;
    }
}
