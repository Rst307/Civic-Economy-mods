package org.civiceconomy.integration.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.data.CustomSaveData;
import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.civiceconomy.fiscal.AccountBalances;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

public final class LightmansCurrencyAccountBalances implements AccountBalances {
    private static final String PLAYER_ACCOUNT_PREFIX = "player:";

    private final AccountBalances fiscalAccountBalances;
    private final PlayerAccountBalances playerAccountBalances;

    LightmansCurrencyAccountBalances(
            AccountBalances fiscalAccountBalances, PlayerAccountBalances playerAccountBalances) {
        this.fiscalAccountBalances = fiscalAccountBalances;
        this.playerAccountBalances = playerAccountBalances;
    }

    public static LightmansCurrencyAccountBalances live(ServerLevel level) {
        return new LightmansCurrencyAccountBalances(
                LightmansCurrencyFiscalAccounts.forLevel(level),
                LightmansCurrencyAccountBalances::livePlayerBalance);
    }

    @Override
    public MoneyAmount balance(AccountId accountId) {
        if (!accountId.value().startsWith(PLAYER_ACCOUNT_PREFIX)) {
            return fiscalAccountBalances.balance(accountId);
        }
        UUID playerId = playerId(accountId);
        return playerAccountBalances
                .balance(playerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown LC player account player:" + playerId));
    }

    private static Optional<MoneyAmount> livePlayerBalance(UUID playerId) {
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        if (!bankData.hasAccount(playerId)) {
            return Optional.empty();
        }
        IBankAccount account = bankData.getAccount(playerId);
        MoneyValue unit = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1);
        long balance = account.getMoneyStorage().valueOf(unit.getUniqueName()).getCoreValue();
        if (balance < 0) {
            throw new IllegalStateException("LC returned a negative player account balance: " + balance);
        }
        return Optional.of(MoneyAmount.ofMinorUnits(balance));
    }

    private static UUID playerId(AccountId accountId) {
        String value = accountId.value();
        try {
            return UUID.fromString(value.substring(PLAYER_ACCOUNT_PREFIX.length()));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid LC player account: " + value, failure);
        }
    }

    @FunctionalInterface
    interface PlayerAccountBalances {
        Optional<MoneyAmount> balance(UUID playerId);
    }
}
