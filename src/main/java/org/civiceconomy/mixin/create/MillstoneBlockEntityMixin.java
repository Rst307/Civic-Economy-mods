package org.civiceconomy.mixin.create;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.civiceconomy.platform.neoforge.CreateMillstoneObservationBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
        targets = "com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity",
        remap = false)
public abstract class MillstoneBlockEntityMixin {
    @Shadow(remap = false)
    public ItemStackHandler inputInv;

    @Shadow(remap = false)
    public ItemStackHandler outputInv;

    @Unique private ItemStack civic$inputBefore = ItemStack.EMPTY;
    @Unique private List<ItemStack> civic$outputBefore = List.of();

    @Inject(method = "process()V", at = @At("HEAD"), remap = false)
    private void civic$beforeProcess(CallbackInfo callback) {
        civic$inputBefore = inputInv.getStackInSlot(0).copy();
        civic$outputBefore = CreateMillstoneObservationBridge.snapshot(outputInv);
    }

    @Inject(method = "process()V", at = @At("RETURN"), remap = false)
    private void civic$afterProcess(CallbackInfo callback) {
        CreateMillstoneObservationBridge.completed(
                this,
                (BlockEntity) (Object) this,
                civic$inputBefore,
                civic$outputBefore,
                inputInv.getStackInSlot(0).copy(),
                CreateMillstoneObservationBridge.snapshot(outputInv));
    }
}
