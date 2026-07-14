package org.civiceconomy.integration.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.data.CustomSaveData;
import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.monetary.ExternalPermanentDestruction;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightmansCurrencyPermanentDestructionsGameTests {
    private LightmansCurrencyPermanentDestructionsGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realFiscalAccountDestructionIsIdempotentAndPersistsItsMarker(
            GameTestHelper helper) {
        UUID fundingPlayerId = UUID.randomUUID();
        UUID cleanupPlayerId = UUID.randomUUID();
        UUID destructionId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        AccountId fundingAccount = new AccountId("player:" + fundingPlayerId);
        AccountId cleanupAccount = new AccountId("player:" + cleanupPlayerId);
        AccountId treasury = new AccountId("nation:" + UUID.randomUUID() + ":treasury");
        LightmansCurrencyFiscalAccounts accounts =
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "Destruction GameTest Treasury");
        reset(bankData, fundingPlayerId, 1_000L);
        reset(bankData, cleanupPlayerId, 0L);
        LightmansCurrencyPayments payments = LightmansCurrencyPayments.live(helper.getLevel());
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), fundingAccount, treasury, MoneyAmount.ofMinorUnits(1_000L)));
        ExternalPermanentDestruction destruction = new ExternalPermanentDestruction(
                destructionId, treasury, MoneyAmount.ofMinorUnits(600L));

        LightmansCurrencyPermanentDestructions.live(helper.getLevel()).apply(destruction);
        LightmansCurrencyPermanentDestructions.live(helper.getLevel()).apply(destruction);

        helper.assertValueEqual(
                400L, accounts.balance(treasury).minorUnits(), "real LC balance after destruction replay");
        CivicFiscalAccountData liveData = accounts.data();
        helper.assertTrue(
                liveData.wasPermanentDestructionApplied(destructionId),
                "live fiscal SavedData should contain the destruction marker");
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CivicFiscalAccountData reloaded =
                CivicFiscalAccountData.load(liveData.save(new CompoundTag(), registries), registries);
        helper.assertTrue(
                reloaded.wasPermanentDestructionApplied(destructionId),
                "serialized fiscal SavedData should reload the destruction marker");
        helper.assertValueEqual(
                400L,
                mainChainBalance(reloaded.account(treasury)),
                "serialized fiscal SavedData should reload the destroyed LC balance");

        payments.apply(new ExternalPayment(
                UUID.randomUUID(), treasury, cleanupAccount, MoneyAmount.ofMinorUnits(400L)));
        bankData.deleteAccount(fundingPlayerId);
        bankData.deleteAccount(cleanupPlayerId);
        helper.succeed();
    }

    private static IBankAccount reset(BankDataCache bankData, UUID playerId, long balance) {
        IBankAccount account = bankData.getAccount(playerId);
        account.getMoneyStorage().clear();
        if (balance > 0L) {
            MoneyValue value = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, balance);
            if (!BankAPI.getApi().BankDepositFromServer(account, value)) {
                throw new IllegalStateException("Unable to seed LC GameTest account " + playerId);
            }
        }
        return account;
    }

    private static long mainChainBalance(IBankAccount account) {
        MoneyValue unit = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1L);
        return account.getMoneyStorage().valueOf(unit.getUniqueName()).getCoreValue();
    }
}
