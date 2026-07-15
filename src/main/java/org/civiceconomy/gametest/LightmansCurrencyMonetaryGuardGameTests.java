package org.civiceconomy.gametest;

import com.mojang.authlib.GameProfile;
import io.github.lightman314.lightmanscurrency.LCConfig;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.bank.BankAccount;
import io.github.lightman314.lightmanscurrency.common.blockentity.CoinMintBlockEntity;
import io.github.lightman314.lightmanscurrency.common.core.ModItems;
import io.github.lightman314.lightmanscurrency.common.data.CustomSaveData;
import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.civiceconomy.CivicEconomy;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightmansCurrencyMonetaryGuardGameTests {
    private LightmansCurrencyMonetaryGuardGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unsafeNativeIssuanceConfigurationIsForcedOff(GameTestHelper helper) {
        helper.assertFalse(LCConfig.COMMON.canCraftCoinMint.get(), "LC Coin Mint crafting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintCanMint.get(), "LC Coin Mint minting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintCanMelt.get(), "LC Coin Mint melting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMintableCopper.get(), "LC copper minting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMintableIron.get(), "LC iron minting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMintableGold.get(), "LC gold minting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMintableEmerald.get(), "LC emerald minting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMintableDiamond.get(), "LC diamond minting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMintableNetherite.get(), "LC netherite minting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMeltableCopper.get(), "LC copper melting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMeltableIron.get(), "LC iron melting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMeltableGold.get(), "LC gold melting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMeltableEmerald.get(), "LC emerald melting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMeltableDiamond.get(), "LC diamond melting must be disabled");
        helper.assertFalse(LCConfig.COMMON.coinMintMeltableNetherite.get(), "LC netherite melting must be disabled");
        helper.assertFalse(LCConfig.COMMON.enableEntityDrops.get(), "LC entity coin drops must be disabled");
        helper.assertFalse(
                LCConfig.COMMON.allowSpawnerEntityDrops.get(),
                "LC spawner entity coin drops must be disabled");
        helper.assertFalse(
                LCConfig.COMMON.allowFakePlayerCoinDrops.get(),
                "LC fake-player coin drops must be disabled");
        helper.assertFalse(LCConfig.COMMON.enableChestLoot.get(), "LC chest coin loot must be disabled");
        helper.assertFalse(
                LCConfig.COMMON.addCustomWanderingTrades.get(),
                "LC custom wandering trades must be disabled");
        helper.assertFalse(LCConfig.COMMON.addBankerVillager.get(), "LC banker trades must be disabled");
        helper.assertFalse(LCConfig.COMMON.addCashierVillager.get(), "LC cashier trades must be disabled");
        helper.assertFalse(LCConfig.COMMON.changeVanillaTrades.get(), "LC vanilla trade rewrites must be disabled");
        helper.assertFalse(LCConfig.COMMON.changeModdedTrades.get(), "LC modded trade rewrites must be disabled");
        helper.assertFalse(
                LCConfig.COMMON.changeWanderingTrades.get(),
                "LC wandering trade rewrites must be disabled");
        helper.assertFalse(LCConfig.COMMON.chocolateEventCoins.get(), "LC seasonal coins must be disabled");
        helper.assertFalse(LCConfig.COMMON.eventStartingRewards.get(), "LC starting rewards must be disabled");
        helper.assertFalse(
                LCConfig.COMMON.eventLootReplacements.get(),
                "LC seasonal loot replacements must be disabled");
        helper.assertValueEqual(
                0.0D,
                LCConfig.SERVER.bankAccountInterestRate.get(),
                "LC bank interest rate");
        helper.assertFalse(
                LCConfig.SERVER.bankAccountForceInterest.get(),
                "LC forced bank interest must be disabled");
        helper.assertFalse(
                LCConfig.SERVER.bankAccountInterestNotification.get(),
                "LC bank interest notification must be disabled with interest");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeCoinMintHasNoUsableRecipes(GameTestHelper helper) {
        helper.assertTrue(
                CoinMintBlockEntity.getCoinMintRecipes(helper.getLevel()).isEmpty(),
                "LC native Coin Mint recipes must be unavailable");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativePlayerBankInterestCannotIssueMoney(GameTestHelper helper) {
        BankAccount account = new BankAccount();
        MoneyValue openingBalance = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 100L);
        account.depositMoney(openingBalance);

        account.applyInterest(1.0D, List.of(), List.of(), true, false);

        helper.assertValueEqual(
                100L,
                account.getMoneyStorage().valueOf(openingBalance.getUniqueName()).getCoreValue(),
                "LC native player bank balance after interest");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void vanillaGiveCannotIssueLightmansCurrencyCoins(GameTestHelper helper) {
        ServerPlayer player = testPlayer(helper, "civic-lc-give-guard");
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
                "give @s lightmanscurrency:coin_copper 1");

        helper.assertValueEqual(
                0,
                player.getInventory().countItem(ModItems.COIN_COPPER.get()),
                "LC copper coins issued through vanilla /give");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void lightmansCurrencyBankGiveCannotIssueMoney(GameTestHelper helper) {
        ServerPlayer player = testPlayer(helper, "civic-lc-bank-give-guard");
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        BankAccount account = bankData.getAccount(player.getUUID());
        account.getMoneyStorage().clear();

        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
                "lcbank give players @s coin;1-lightmanscurrency:coin_copper");

        helper.assertValueEqual(
                0L,
                account.getMoneyStorage()
                        .valueOf(CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1L).getUniqueName())
                        .getCoreValue(),
                "LC player-bank balance after /lcbank give");
        bankData.deleteAccount(player.getUUID());
        helper.succeed();
    }

    private static ServerPlayer testPlayer(GameTestHelper helper, String name) {
        return new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault());
    }
}
