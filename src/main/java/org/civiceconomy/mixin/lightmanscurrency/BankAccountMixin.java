package org.civiceconomy.mixin.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.common.bank.BankAccount;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BankAccount.class, remap = false)
public abstract class BankAccountMixin {
    @Inject(method = "applyInterest", at = @At("HEAD"), cancellable = true, remap = false)
    private void civicEconomy$denyInterestIssuance(
            double interestMultiplier,
            List<MoneyValue> limits,
            List<String> blacklist,
            boolean forceInterest,
            boolean sendNotification,
            CallbackInfo callback) {
        callback.cancel();
    }
}
