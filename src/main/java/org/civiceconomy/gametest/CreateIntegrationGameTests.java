package org.civiceconomy.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.platform.neoforge.CreateMillstoneObservationBridge;
import org.civiceconomy.production.CreateRecipeCompletion;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateIntegrationGameTests {
    private CreateIntegrationGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void realMillstoneProcessEmitsExactInventoryCompletion(
            GameTestHelper helper) {
        if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
            helper.succeed();
            return;
        }
        AtomicReference<CreateRecipeCompletion> observed = new AtomicReference<>();
        CreateMillstoneObservationBridge.install(
                completion -> {
                    if (!observed.compareAndSet(null, completion)) {
                        throw new IllegalStateException(
                                "One Millstone process emitted multiple observations");
                    }
                },
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
        try {
            BlockPos position = new BlockPos(1, 1, 1);
            Block millstone = BuiltInRegistries.BLOCK.get(
                    ResourceLocation.parse("create:millstone"));
            helper.setBlock(position, millstone.defaultBlockState());
            BlockEntity blockEntity = helper.getBlockEntity(position);
            helper.assertTrue(
                    blockEntity.getClass().getName().equals(
                            CreateMillstoneObservationBridge.MILLSTONE_CLASS),
                    "real Create Millstone block entity is present");

            Field inputField = blockEntity.getClass().getDeclaredField("inputInv");
            inputField.setAccessible(true);
            ItemStackHandler input = (ItemStackHandler) inputField.get(blockEntity);
            input.setStackInSlot(0, new ItemStack(Items.WHEAT, 1));

            Method process = blockEntity.getClass().getDeclaredMethod("process");
            process.setAccessible(true);
            process.invoke(blockEntity);

            CreateRecipeCompletion completion = observed.get();
            helper.assertTrue(completion != null, "Millstone completion was observed");
            helper.assertTrue(
                    completion.recipeId().startsWith("create:"),
                    "completion records the real Create recipe ID");
            helper.assertValueEqual(
                    "minecraft:wheat",
                    completion.inventoryDelta().inputs().getFirst().stack().itemId(),
                    "completion records the actual consumed input");
            helper.assertTrue(
                    !completion.inventoryDelta().outputs().isEmpty(),
                    "completion records actual inserted output");
            helper.succeed();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to drive the pinned Create Millstone", failure);
        } finally {
            CreateMillstoneObservationBridge.reset();
        }
    }
}
