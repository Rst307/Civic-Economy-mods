package org.civiceconomy.platform.neoforge;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.civiceconomy.production.FacilityAccountingInterfacePosition;
import org.civiceconomy.production.FacilityAccountingReceiptDetector;
import org.civiceconomy.production.ProductionStack;

final class FacilityAccountingInterfaceObservationBridge {
    private static final FacilityAccountingReceiptDetector DETECTOR =
            new FacilityAccountingReceiptDetector();
    private static volatile Consumer<FacilityAccountingInterfaceInventoryIncrease> runtimeSink =
            ignored -> {};
    private static volatile Clock clock = Clock.systemUTC();

    private FacilityAccountingInterfaceObservationBridge() {}

    static void installRuntime(
            Consumer<FacilityAccountingInterfaceInventoryIncrease> observationSink,
            Clock observationClock) {
        if (observationSink == null || observationClock == null) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface observation dependencies are required");
        }
        runtimeSink = observationSink;
        clock = observationClock;
    }

    static void resetRuntime() {
        runtimeSink = ignored -> {};
        clock = Clock.systemUTC();
    }

    static List<ItemStack> snapshot(FacilityAccountingInterfaceBlockEntity blockEntity) {
        List<ItemStack> snapshot = new ArrayList<>(blockEntity.getContainerSize());
        for (int slot = 0; slot < blockEntity.getContainerSize(); slot++) {
            snapshot.add(blockEntity.getItem(slot).copy());
        }
        return List.copyOf(snapshot);
    }

    static void observe(
            Level level,
            BlockPos position,
            List<ItemStack> before,
            List<ItemStack> after) {
        if (level == null || level.isClientSide() || position == null) {
            return;
        }
        DETECTOR.detect(toProductionStacks(before, level), toProductionStacks(after, level))
                .ifPresent(changes -> runtimeSink.accept(
                        new FacilityAccountingInterfaceInventoryIncrease(
                                new FacilityAccountingInterfacePosition(
                                        level.dimension().location().toString(),
                                        position.getX(),
                                        position.getY(),
                                        position.getZ()),
                                clock.millis(),
                                changes)));
    }

    private static List<ProductionStack> toProductionStacks(
            List<ItemStack> stacks, Level level) {
        if (stacks == null) {
            return List.of();
        }
        return stacks.stream()
                .map(stack -> toProductionStack(stack, level))
                .toList();
    }

    private static ProductionStack toProductionStack(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty()) {
            return ProductionStack.EMPTY;
        }
        ItemStack identity = stack.copyWithCount(1);
        return new ProductionStack(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                identity.saveOptional(level.registryAccess()).toString(),
                stack.getCount());
    }
}
