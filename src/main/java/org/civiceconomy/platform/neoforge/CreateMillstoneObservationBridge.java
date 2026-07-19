package org.civiceconomy.platform.neoforge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.civiceconomy.production.CreateMachineKind;
import org.civiceconomy.production.CreateRecipeCompletion;
import org.civiceconomy.production.MillstoneCompletionDetector;
import org.civiceconomy.production.ProductionStack;

public final class CreateMillstoneObservationBridge {
    public static final String MILLSTONE_CLASS =
            "com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity";
    public static final String MILLING_RECIPE_CLASS =
            "com.simibubi.create.content.kinetics.millstone.MillingRecipe";
    public static final String CREATE_VERSION = "6.0.6";

    private static final MillstoneCompletionDetector DETECTOR =
            new MillstoneCompletionDetector();
    private static volatile Consumer<CreateRecipeCompletion> runtimeSink = ignored -> {};
    private static volatile Consumer<CreateRecipeCompletion> testSink = ignored -> {};
    private static volatile Clock runtimeClock = Clock.systemUTC();
    private static volatile Clock testClock = Clock.systemUTC();

    private CreateMillstoneObservationBridge() {}

    public static Optional<String> verifyRuntimeContract() {
        try {
            Class<?> millstone = Class.forName(MILLSTONE_CLASS, false,
                    CreateMillstoneObservationBridge.class.getClassLoader());
            Class<?> recipe = Class.forName(MILLING_RECIPE_CLASS, false,
                    CreateMillstoneObservationBridge.class.getClassLoader());
            Method process = millstone.getDeclaredMethod("process");
            Field input = millstone.getDeclaredField("inputInv");
            Field output = millstone.getDeclaredField("outputInv");
            Field lastRecipe = millstone.getDeclaredField("lastRecipe");
            if (process.getReturnType() != void.class
                    || !input.getType().getName().equals(
                            "net.neoforged.neoforge.items.ItemStackHandler")
                    || !output.getType().getName().equals(
                            "net.neoforged.neoforge.items.ItemStackHandler")
                    || lastRecipe.getType() != recipe) {
                return Optional.of("Create Millstone descriptor drift");
            }
            return Optional.empty();
        } catch (ReflectiveOperationException | LinkageError failure) {
            return Optional.of("Create Millstone contract unavailable: " + failure);
        }
    }

    public static void install(
            Consumer<CreateRecipeCompletion> observationSink, Clock observationClock) {
        if (observationSink == null || observationClock == null) {
            throw new IllegalArgumentException("Create observation sink cannot be null");
        }
        testSink = observationSink;
        testClock = observationClock;
    }

    static void installRuntime(
            Consumer<CreateRecipeCompletion> observationSink, Clock observationClock) {
        if (observationSink == null || observationClock == null) {
            throw new IllegalArgumentException("Create observation sink cannot be null");
        }
        runtimeSink = observationSink;
        runtimeClock = observationClock;
    }

    public static void reset() {
        testSink = ignored -> {};
        testClock = Clock.systemUTC();
    }

    static void resetRuntime() {
        runtimeSink = ignored -> {};
        runtimeClock = Clock.systemUTC();
    }

    public static List<ItemStack> snapshot(
            net.neoforged.neoforge.items.ItemStackHandler inventory) {
        List<ItemStack> stacks = new ArrayList<>(inventory.getSlots());
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            stacks.add(inventory.getStackInSlot(slot).copy());
        }
        return List.copyOf(stacks);
    }

    public static void completed(
            Object millstone,
            BlockEntity blockEntity,
            ItemStack inputBefore,
            List<ItemStack> outputBefore,
            ItemStack inputAfter,
            List<ItemStack> outputAfter) {
        if (millstone == null || blockEntity == null || blockEntity.getLevel() == null
                || blockEntity.getLevel().isClientSide()) {
            return;
        }
        var level = blockEntity.getLevel();
        DETECTOR.detect(
                        toProductionStack(inputBefore, level),
                        toProductionStack(inputAfter, level),
                        outputBefore.stream()
                                .map(stack -> toProductionStack(stack, level))
                                .toList(),
                        outputAfter.stream()
                                .map(stack -> toProductionStack(stack, level))
                                .toList())
                .ifPresent(delta -> recipeId(millstone, level.getRecipeManager().getRecipes())
                        .ifPresent(recipeId -> {
                            UUID observationId = UUID.randomUUID();
                            runtimeSink.accept(completion(
                                    observationId, recipeId, blockEntity, runtimeClock, delta));
                            testSink.accept(completion(
                                    observationId, recipeId, blockEntity, testClock, delta));
                        }));
    }

    private static CreateRecipeCompletion completion(
            UUID observationId,
            String recipeId,
            BlockEntity blockEntity,
            Clock observationClock,
            org.civiceconomy.production.MachineInventoryDelta delta) {
        return new CreateRecipeCompletion(
                observationId,
                CREATE_VERSION,
                CreateMachineKind.MILLSTONE,
                recipeId,
                blockEntity.getLevel().dimension().location().toString(),
                blockEntity.getBlockPos().getX(),
                blockEntity.getBlockPos().getY(),
                blockEntity.getBlockPos().getZ(),
                observationClock.millis(),
                delta);
    }

    private static ProductionStack toProductionStack(
            ItemStack stack, net.minecraft.world.level.Level level) {
        if (stack == null || stack.isEmpty()) {
            return ProductionStack.EMPTY;
        }
        ItemStack identity = stack.copyWithCount(1);
        return new ProductionStack(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                identity.saveOptional(level.registryAccess()).toString(),
                stack.getCount());
    }

    private static Optional<String> recipeId(
            Object millstone, java.util.Collection<RecipeHolder<?>> recipes) {
        try {
            Field field = millstone.getClass().getDeclaredField("lastRecipe");
            field.setAccessible(true);
            Object currentRecipe = field.get(millstone);
            if (currentRecipe == null) {
                return Optional.empty();
            }
            return recipes.stream()
                    .filter(holder -> holder.value() == currentRecipe)
                    .map(holder -> holder.id().toString())
                    .findFirst();
        } catch (ReflectiveOperationException | LinkageError failure) {
            return Optional.empty();
        }
    }
}
