package org.civiceconomy.mixin.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.common.loot.glm.CoinsInChestsModifier;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CoinsInChestsModifier.class, remap = false)
public abstract class CoinsInChestsModifierMixin {
    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void civicEconomy$denyChestCoinLoot(
            ObjectArrayList<ItemStack> generatedLoot,
            LootContext context,
            CallbackInfoReturnable<ObjectArrayList<ItemStack>> callback) {
        callback.setReturnValue(generatedLoot);
    }
}
