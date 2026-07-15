package org.civiceconomy.mixin.lightmanscurrency;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.GiveCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GiveCommand.class)
public abstract class GiveCommandMixin {
    @Inject(method = "giveItem", at = @At("HEAD"), cancellable = true)
    private static void civicEconomy$denyUnauditedCoinGive(
            CommandSourceStack source,
            ItemInput input,
            Collection<ServerPlayer> targets,
            int count,
            CallbackInfoReturnable<Integer> callback) throws CommandSyntaxException {
        ItemStack sample = input.createItemStack(1, false);
        if (CoinAPI.getApi().IsAllowedInCoinContainer(sample, false)) {
            source.sendFailure(Component.literal(
                    "Civic Economy blocks /give for LC money; use an audited Civic stock-correction command"));
            callback.setReturnValue(0);
        }
    }
}
