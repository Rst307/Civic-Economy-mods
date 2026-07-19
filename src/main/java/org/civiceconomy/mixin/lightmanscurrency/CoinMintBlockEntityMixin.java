package org.civiceconomy.mixin.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.common.blockentity.CoinMintBlockEntity;
import io.github.lightman314.lightmanscurrency.common.crafting.CoinMintRecipe;
import java.util.List;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CoinMintBlockEntity.class, remap = false)
public abstract class CoinMintBlockEntityMixin {
    @Inject(
            method = "getCoinMintRecipes(Lnet/minecraft/world/level/Level;)Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void civicEconomy$hideNativeMintRecipes(
            Level level, CallbackInfoReturnable<List<CoinMintRecipe>> callback) {
        callback.setReturnValue(List.of());
    }

    @Inject(method = "mintCoin", at = @At("HEAD"), cancellable = true, remap = false)
    private void civicEconomy$denyNativeMintAndMelt(CallbackInfo callback) {
        callback.cancel();
    }
}
