package org.civiceconomy.mixin.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.common.villager_merchant.VillagerTradeManager;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.event.village.WandererTradesEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VillagerTradeManager.class, remap = false)
public abstract class VillagerTradeManagerMixin {
    @Inject(method = "OnVillagerTradeSetup", at = @At("HEAD"), cancellable = true, remap = false)
    private static void civicEconomy$denyCoinVillagerTrades(
            VillagerTradesEvent event, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "OnWandererTradeSetup", at = @At("HEAD"), cancellable = true, remap = false)
    private static void civicEconomy$denyCoinWanderingTrades(
            WandererTradesEvent event, CallbackInfo callback) {
        callback.cancel();
    }
}
