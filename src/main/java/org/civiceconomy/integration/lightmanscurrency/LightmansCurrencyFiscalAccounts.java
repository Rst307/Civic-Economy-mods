package org.civiceconomy.integration.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.civiceconomy.fiscal.AccountBalances;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

public final class LightmansCurrencyFiscalAccounts implements AccountBalances {
    private static boolean registered;

    private final CivicFiscalAccountData data;

    private LightmansCurrencyFiscalAccounts(CivicFiscalAccountData data) {
        this.data = data;
    }

    public static synchronized void registerWithLightmansCurrency() {
        if (registered) {
            return;
        }
        BankAPI bankApi = BankAPI.getApi();
        var existingType = bankApi.GetReferenceType(CivicFiscalAccountReference.TYPE.id);
        if (existingType == null) {
            bankApi.RegisterReferenceType(CivicFiscalAccountReference.TYPE);
        } else if (existingType != CivicFiscalAccountReference.TYPE) {
            throw new IllegalStateException(
                    "LC bank reference type collision at " + CivicFiscalAccountReference.TYPE.id);
        }
        bankApi.RegisterBankAccountSource(CivicFiscalAccountSource.INSTANCE);
        registered = true;
    }

    public static LightmansCurrencyFiscalAccounts forLevel(ServerLevel level) {
        CivicFiscalAccountData data = level.getServer()
                .overworld()
                .getDataStorage()
                .computeIfAbsent(CivicFiscalAccountData.FACTORY, CivicFiscalAccountData.DATA_NAME);
        return new LightmansCurrencyFiscalAccounts(data);
    }

    public static LightmansCurrencyFiscalAccounts live() {
        LightmansCurrencyFiscalAccounts accounts = liveOrNull();
        if (accounts == null) {
            throw new IllegalStateException("No Minecraft server is currently running");
        }
        return accounts;
    }

    static LightmansCurrencyFiscalAccounts liveOrNull() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : forLevel(server.overworld());
    }

    public void create(AccountId accountId, FiscalAccountKind kind, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Fiscal account display name cannot be blank");
        }
        data.create(accountId, kind, displayName);
    }

    @Override
    public MoneyAmount balance(AccountId accountId) {
        IBankAccount account = requireAccount(accountId);
        MoneyValue unit = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1);
        long balance = account.getMoneyStorage().valueOf(unit.getUniqueName()).getCoreValue();
        if (balance < 0) {
            throw new IllegalStateException("LC returned a negative fiscal account balance: " + balance);
        }
        return MoneyAmount.ofMinorUnits(balance);
    }

    IBankAccount accountOrNull(AccountId accountId) {
        return data.account(accountId);
    }

    IBankAccount requireAccount(AccountId accountId) {
        IBankAccount account = accountOrNull(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Unknown Civic fiscal account " + accountId.value());
        }
        return account;
    }

    List<AccountId> accountIds() {
        return data.accountIds();
    }

    CivicFiscalAccountData data() {
        return data;
    }

    boolean wasPermanentDestructionApplied(UUID destructionId) {
        return data.wasPermanentDestructionApplied(destructionId);
    }

    void recordPermanentDestructionApplied(UUID destructionId) {
        data.recordPermanentDestructionApplied(destructionId);
    }
}
