package org.civiceconomy.mixin.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.common.loot.LootManager;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LootManager.class, remap = false)
public abstract class LootManagerMixin {
    @Inject(method = "onEntityDeath", at = @At("HEAD"), cancellable = true, remap = false)
    private static void civicEconomy$denyEntityCoinDrops(
            LivingDeathEvent event, CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(method = "checkForEventReplacements", at = @At("HEAD"), cancellable = true, remap = false)
    private static void civicEconomy$denySeasonalLootReplacement(
            MinecraftServer server, List<ItemStack> loot, CallbackInfo callback) {
        callback.cancel();
    }
}
