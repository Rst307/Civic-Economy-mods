package org.civiceconomy.mixin.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.common.commands.CommandBank;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CommandBank.class, remap = false)
public abstract class CommandBankMixin {
    @Inject(method = "giveTo", at = @At("HEAD"), cancellable = true, remap = false)
    private static void civicEconomy$denyUnauditedBankIssuance(
            CommandSourceStack source,
            List<BankReference> accounts,
            MoneyValue amount,
            boolean notifyPlayers,
            CallbackInfoReturnable<Integer> callback) {
        source.sendFailure(Component.literal(
                "Civic Economy blocks /lcbank give; use an audited Civic stock-correction command"));
        callback.setReturnValue(0);
    }
}
