package org.civiceconomy.mixin.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.config.options.ConfigOption;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyMonetaryGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ConfigOption.class, remap = false)
public abstract class ConfigOptionMixin {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true, remap = false)
    private void civicEconomy$forceConservationSafeValue(
            CallbackInfoReturnable<Object> callback) {
        ConfigOption<?> option = (ConfigOption<?>) (Object) this;
        if (LightmansCurrencyMonetaryGuard.forcesFalse(option)) {
            callback.setReturnValue(false);
        } else if (LightmansCurrencyMonetaryGuard.forcesZeroInterest(option)) {
            callback.setReturnValue(0.0D);
        }
    }
}
