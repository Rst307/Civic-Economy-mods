package org.civiceconomy.integration.lightmanscurrency;

import com.mojang.logging.LogUtils;
import io.github.lightman314.lightmanscurrency.LCConfig;
import io.github.lightman314.lightmanscurrency.api.config.options.ConfigOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Pins the supported Lightman's Currency runtime to conservation-safe settings.
 * Method-level mixins remain the final authority when an option is changed or reloaded.
 */
public final class LightmansCurrencyMonetaryGuard {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private LightmansCurrencyMonetaryGuard() {}

    public static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            LOGGER.info(
                    "Civic Economy installed the LC monetary guard: native mint/melt, free loot, "
                            + "coin-generating trades, seasonal rewards, and bank interest are disabled");
        }
    }

    public static boolean forcesFalse(ConfigOption<?> option) {
        LCConfig.Common common = LCConfig.COMMON;
        if (common != null
                && (option == common.canCraftCoinMint
                        || option == common.coinMintCanMint
                        || option == common.coinMintCanMelt
                        || option == common.coinMintMintableCopper
                        || option == common.coinMintMintableIron
                        || option == common.coinMintMintableGold
                        || option == common.coinMintMintableEmerald
                        || option == common.coinMintMintableDiamond
                        || option == common.coinMintMintableNetherite
                        || option == common.coinMintMeltableCopper
                        || option == common.coinMintMeltableIron
                        || option == common.coinMintMeltableGold
                        || option == common.coinMintMeltableEmerald
                        || option == common.coinMintMeltableDiamond
                        || option == common.coinMintMeltableNetherite
                        || option == common.enableEntityDrops
                        || option == common.allowSpawnerEntityDrops
                        || option == common.allowFakePlayerCoinDrops
                        || option == common.enableChestLoot
                        || option == common.addCustomWanderingTrades
                        || option == common.addBankerVillager
                        || option == common.addCashierVillager
                        || option == common.changeVanillaTrades
                        || option == common.changeModdedTrades
                        || option == common.changeWanderingTrades
                        || option == common.chocolateEventCoins
                        || option == common.eventStartingRewards
                        || option == common.eventLootReplacements)) {
            return true;
        }
        LCConfig.Server server = LCConfig.SERVER;
        return server != null
                && (option == server.bankAccountForceInterest
                        || option == server.bankAccountInterestNotification);
    }

    public static boolean forcesZeroInterest(ConfigOption<?> option) {
        LCConfig.Server server = LCConfig.SERVER;
        return server != null && option == server.bankAccountInterestRate;
    }
}
