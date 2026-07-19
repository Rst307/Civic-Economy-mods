package org.civiceconomy.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.platform.neoforge.CreateMillstoneObservationBridge;
import org.civiceconomy.platform.neoforge.FacilityAccountingInterfaceBlockEntity;
import org.civiceconomy.platform.neoforge.CivicContent;
import org.civiceconomy.platform.neoforge.ServerFacilityAccountingBaselineSnapshotSource;
import org.civiceconomy.production.CreateRecipeCompletion;
import org.civiceconomy.production.CreateMachineKind;
import org.civiceconomy.production.FacilityAccountingInterface;
import org.civiceconomy.production.FacilityAccountingInterfacePosition;
import org.civiceconomy.production.FacilityCorePosition;
import org.civiceconomy.production.RegisteredFacility;
import org.civiceconomy.production.RegisteredFacilityState;
import org.civiceconomy.territory.TerritoryClaimPosition;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateIntegrationGameTests {
    private CreateIntegrationGameTests() {}

    @GameTest(template = "empty")
    public static void facilitySnapshotRejectsOrdinaryChestAsAccountingInterface(
            GameTestHelper helper) {
        if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
            helper.succeed();
            return;
        }
        BlockPos interfacePosition = new BlockPos(1, 1, 1);
        BlockPos millstonePosition = new BlockPos(2, 1, 1);
        helper.setBlock(interfacePosition, Blocks.CHEST);
        helper.setBlock(
                millstonePosition,
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:millstone")));
        BlockPos absoluteInterface = helper.absolutePos(interfacePosition);
        BlockPos absoluteMillstone = helper.absolutePos(millstonePosition);
        String dimensionId = helper.getLevel().dimension().location().toString();
        TerritoryClaimPosition claim = new TerritoryClaimPosition(
                dimensionId, absoluteInterface.getX() >> 4, absoluteInterface.getZ() >> 4);
        RegisteredFacility facility = new RegisteredFacility(
                UUID.randomUUID(),
                new ServiceIdentity("civiceconomy-interface-security-gametest"),
                "reject-chest-facility",
                new NationId(UUID.randomUUID()),
                UUID.randomUUID(),
                new FacilityCorePosition(
                        dimensionId,
                        absoluteMillstone.getX(),
                        absoluteMillstone.getY(),
                        absoluteMillstone.getZ()),
                Set.of(claim),
                UUID.randomUUID(),
                RegisteredFacilityState.BASELINING,
                "reject ordinary chest",
                Instant.parse("2026-09-01T00:00:00Z"));
        FacilityAccountingInterface accountingInterface = new FacilityAccountingInterface(
                UUID.randomUUID(),
                facility.serviceIdentity(),
                "reject-chest-interface",
                facility.facilityId(),
                new FacilityAccountingInterfacePosition(
                        dimensionId,
                        absoluteInterface.getX(),
                        absoluteInterface.getY(),
                        absoluteInterface.getZ()),
                facility.actorPlayerId(),
                "reject ordinary chest",
                Instant.parse("2026-09-01T00:00:00Z"));

        try {
            new ServerFacilityAccountingBaselineSnapshotSource(helper.getLevel().getServer())
                    .capture(facility, accountingInterface);
            helper.fail("ordinary chest was accepted as a Facility Accounting Interface");
        } catch (SecurityException expected) {
            helper.assertTrue(
                    expected.getMessage().contains("real Civic interface block"),
                    "ordinary chest fails closed as an accounting interface");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void realFacilitySnapshotCapturesCivicInterfaceAndMillstone(
            GameTestHelper helper) {
        if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
            helper.succeed();
            return;
        }
        BlockPos interfacePosition = new BlockPos(1, 1, 1);
        BlockPos millstonePosition = new BlockPos(2, 1, 1);
        helper.setBlock(interfacePosition, CivicContent.FACILITY_ACCOUNTING_INTERFACE.get());
        FacilityAccountingInterfaceBlockEntity accountingInventory =
                helper.getBlockEntity(interfacePosition);
        ItemStack namedDiamonds = new ItemStack(Items.DIAMOND, 7);
        namedDiamonds.set(DataComponents.CUSTOM_NAME, Component.literal("Audited Batch"));
        accountingInventory.setItem(4, namedDiamonds);
        accountingInventory.setItem(5, new ItemStack(Items.DIAMOND, 3));
        helper.setBlock(
                millstonePosition,
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:millstone")));

        BlockPos absoluteInterface = helper.absolutePos(interfacePosition);
        BlockPos absoluteMillstone = helper.absolutePos(millstonePosition);
        ChunkPos facilityChunk = new ChunkPos(absoluteMillstone);
        ChunkPos outsideScopeChunk = new ChunkPos(facilityChunk.x + 32, facilityChunk.z);
        helper.getLevel().getChunk(outsideScopeChunk.x, outsideScopeChunk.z);
        BlockPos outsideScopeMillstone = new BlockPos(
                outsideScopeChunk.getMinBlockX() + 1,
                absoluteMillstone.getY(),
                outsideScopeChunk.getMinBlockZ() + 1);
        helper.getLevel().setBlockAndUpdate(
                outsideScopeMillstone,
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:millstone"))
                        .defaultBlockState());
        String dimensionId = helper.getLevel().dimension().location().toString();
        RegisteredFacility facility = new RegisteredFacility(
                UUID.randomUUID(),
                new ServiceIdentity("civiceconomy-facility-gametest"),
                "facility-snapshot",
                new NationId(UUID.randomUUID()),
                UUID.randomUUID(),
                new FacilityCorePosition(
                        dimensionId,
                        absoluteMillstone.getX(),
                        absoluteMillstone.getY(),
                        absoluteMillstone.getZ()),
                Set.copyOf(java.util.List.of(
                        new TerritoryClaimPosition(
                                dimensionId,
                                absoluteInterface.getX() >> 4,
                                absoluteInterface.getZ() >> 4),
                        new TerritoryClaimPosition(
                                dimensionId,
                                absoluteMillstone.getX() >> 4,
                                absoluteMillstone.getZ() >> 4))),
                UUID.randomUUID(),
                RegisteredFacilityState.BASELINING,
                "real facility snapshot",
                Instant.parse("2026-09-01T00:00:00Z"));
        FacilityAccountingInterface accountingInterface = new FacilityAccountingInterface(
                UUID.randomUUID(),
                facility.serviceIdentity(),
                "interface-snapshot",
                facility.facilityId(),
                new FacilityAccountingInterfacePosition(
                        dimensionId,
                        absoluteInterface.getX(),
                        absoluteInterface.getY(),
                        absoluteInterface.getZ()),
                facility.actorPlayerId(),
                "real interface snapshot",
                Instant.parse("2026-09-01T00:00:00Z"));

        var snapshot = new ServerFacilityAccountingBaselineSnapshotSource(
                        helper.getLevel().getServer())
                .capture(facility, accountingInterface);

        helper.assertValueEqual("6.0.6", snapshot.createVersion(), "Create version");
        helper.assertValueEqual(1, snapshot.machines().size(), "supported machine count");
        helper.assertValueEqual(
                CreateMachineKind.MILLSTONE,
                snapshot.machines().getFirst().machineKind(),
                "supported machine kind");
        helper.assertValueEqual(
                absoluteMillstone.getX(),
                snapshot.machines().getFirst().position().blockX(),
                "supported machine X");
        helper.assertValueEqual(2, snapshot.startingInventory().size(), "inventory count");
        helper.assertValueEqual(
                "minecraft:diamond",
                snapshot.startingInventory().getFirst().stack().itemId(),
                "inventory item identity");
        helper.assertValueEqual(
                7,
                snapshot.startingInventory().getFirst().stack().count(),
                "inventory item count");
        helper.assertTrue(
                !snapshot.startingInventory().getFirst().stack().componentFingerprint().equals(
                        snapshot.startingInventory().get(1).stack().componentFingerprint()),
                "same item with different components has a different fingerprint");
        helper.succeed();
    }

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
