package org.civiceconomy.gametest;

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
import org.civiceconomy.integration.lightmanscurrency.CivicBankDataTransactions;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPayments;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightmansCurrencyPlayerPaymentsGameTests {
    private LightmansCurrencyPlayerPaymentsGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realPlayerBankTransferIsIdempotentAndPersistsItsMarker(GameTestHelper helper) {
        UUID sourcePlayer = UUID.fromString("c215241a-5797-4562-b675-80fa477166c5");
        UUID recipientPlayer = UUID.fromString("2b991b9c-ccc7-4a2e-a1b7-f787c3c15909");
        UUID transactionId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        IBankAccount source = reset(bankData, sourcePlayer, 1_000);
        IBankAccount recipient = reset(bankData, recipientPlayer, 25);
        ExternalPayment payment = new ExternalPayment(
                transactionId,
                new AccountId("player:" + sourcePlayer),
                new AccountId("player:" + recipientPlayer),
                MoneyAmount.ofMinorUnits(300));

        LightmansCurrencyPayments.live(helper.getLevel()).apply(payment);
        LightmansCurrencyPayments.live(helper.getLevel()).apply(payment);

        helper.assertValueEqual(700L, mainChainBalance(source), "source LC player-bank balance");
        helper.assertValueEqual(325L, mainChainBalance(recipient), "recipient LC player-bank balance");
        helper.assertTrue(
                ((CivicBankDataTransactions) bankData).civicEconomy$wasApplied(transactionId),
                "live LC bank data should contain the Civic transaction marker");

        bankData.deleteAccount(sourcePlayer);
        bankData.deleteAccount(recipientPlayer);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag saved = new CompoundTag();
        bankData.save(saved, registries);
        saved.remove("PlayerBankData");
        BankDataCache reloaded = BankDataCache.TYPE.create();
        reloaded.initServer(() -> {});
        reloaded.loadData(saved, registries);
        helper.assertTrue(
                ((CivicBankDataTransactions) reloaded).civicEconomy$wasApplied(transactionId),
                "serialized LC bank data should reload the Civic transaction marker");
        helper.succeed();
    }

    private static IBankAccount reset(BankDataCache bankData, UUID playerId, long balance) {
        IBankAccount account = bankData.getAccount(playerId);
        account.getMoneyStorage().clear();
        if (balance > 0) {
            MoneyValue value = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, balance);
            if (!BankAPI.getApi().BankDepositFromServer(account, value)) {
                throw new IllegalStateException("Unable to seed LC GameTest account " + playerId);
            }
        }
        return account;
    }

    private static long mainChainBalance(IBankAccount account) {
        MoneyValue unit = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1);
        return account.getMoneyStorage().valueOf(unit.getUniqueName()).getCoreValue();
    }
}
