package org.civiceconomy.mixin.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.common.data.types.EventRewardDataCache;
import io.github.lightman314.lightmanscurrency.common.seasonal_events.SeasonalEventManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SeasonalEventManager.class, remap = false)
public abstract class SeasonalEventManagerMixin {
    @Inject(method = "checkPlayerRewards", at = @At("HEAD"), cancellable = true, remap = false)
    private static void civicEconomy$denyStartingRewards(
            ServerPlayer player, EventRewardDataCache cache, CallbackInfo callback) {
        callback.cancel();
    }
}
