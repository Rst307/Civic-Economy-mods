package org.civiceconomy.platform.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.civiceconomy.production.CreateMachineKind;
import org.civiceconomy.production.FacilityAccountingBaselineSnapshot;
import org.civiceconomy.production.FacilityAccountingBaselineSnapshotSource;
import org.civiceconomy.production.FacilityAccountingInterface;
import org.civiceconomy.production.FacilityBaselineMachine;
import org.civiceconomy.production.FacilityMachinePosition;
import org.civiceconomy.production.MachineInventoryChange;
import org.civiceconomy.production.ProductionStack;
import org.civiceconomy.production.RegisteredFacility;
import org.civiceconomy.territory.TerritoryClaimPosition;

/**
 * Captures a Facility Accounting Baseline from authoritative loaded server state.
 * It never loads a chunk and never accepts caller-provided machine or inventory facts.
 */
public final class ServerFacilityAccountingBaselineSnapshotSource
        implements FacilityAccountingBaselineSnapshotSource {
    private static final ResourceLocation MILLSTONE_BLOCK =
            ResourceLocation.parse("create:millstone");
    private static final Comparator<FacilityBaselineMachine> MACHINE_ORDER = Comparator
            .comparing((FacilityBaselineMachine machine) -> machine.position().dimensionId())
            .thenComparingInt(machine -> machine.position().blockX())
            .thenComparingInt(machine -> machine.position().blockY())
            .thenComparingInt(machine -> machine.position().blockZ());

    private final MinecraftServer server;

    public ServerFacilityAccountingBaselineSnapshotSource(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("Minecraft server is required");
        }
        this.server = server;
    }

    @Override
    public FacilityAccountingBaselineSnapshot capture(
            RegisteredFacility facility,
            FacilityAccountingInterface accountingInterface) {
        if (facility == null || accountingInterface == null) {
            throw new IllegalArgumentException("Facility snapshot inputs are required");
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Facility snapshot must run on the Minecraft server thread");
        }
        if (!facility.facilityId().equals(accountingInterface.facilityId())) {
            throw new SecurityException(
                    "Facility Accounting Interface is not bound to the requested Facility");
        }

        String createVersion = new NeoForgeModCatalog().version("create")
                .orElseThrow(() -> new IllegalStateException(
                        "Create is unavailable for Facility Accounting Baseline capture"));
        if (!CreateMillstoneObservationBridge.CREATE_VERSION.equals(createVersion)) {
            throw new SecurityException(
                    "Unsupported Create version for Facility Accounting Baseline capture");
        }

        FacilityAccountingInterfaceBlockEntity interfaceBlockEntity =
                requireAccountingInterface(facility, accountingInterface);
        List<FacilityBaselineMachine> machines = captureMachines(facility);
        List<MachineInventoryChange> inventory = captureInventory(interfaceBlockEntity);
        return new FacilityAccountingBaselineSnapshot(
                createVersion,
                machines,
                inventory);
    }

    private FacilityAccountingInterfaceBlockEntity requireAccountingInterface(
            RegisteredFacility facility,
            FacilityAccountingInterface accountingInterface) {
        var position = accountingInterface.position();
        TerritoryClaimPosition claim = position.claim();
        if (!facility.scope().contains(claim)) {
            throw new SecurityException(
                    "Facility Accounting Interface is outside the Registered Facility scope");
        }
        ServerLevel level = requireLevel(position.dimensionId());
        LevelChunk chunk = requireLoadedChunk(level, claim);
        BlockPos blockPosition = new BlockPos(
                position.blockX(), position.blockY(), position.blockZ());
        BlockEntity blockEntity = chunk.getBlockEntity(blockPosition);
        if (!(blockEntity instanceof FacilityAccountingInterfaceBlockEntity accountingBlockEntity)
                || blockEntity.getType()
                        != CivicContent.FACILITY_ACCOUNTING_INTERFACE_BLOCK_ENTITY.get()
                || blockEntity.getBlockState().getBlock()
                        != CivicContent.FACILITY_ACCOUNTING_INTERFACE.get()) {
            throw new SecurityException(
                    "Registered Facility Accounting Interface is not a real Civic interface block");
        }
        return accountingBlockEntity;
    }

    private List<FacilityBaselineMachine> captureMachines(RegisteredFacility facility) {
        List<FacilityBaselineMachine> machines = new ArrayList<>();
        facility.scope().stream()
                .sorted(Comparator.comparing(TerritoryClaimPosition::dimensionId)
                        .thenComparingInt(TerritoryClaimPosition::chunkX)
                        .thenComparingInt(TerritoryClaimPosition::chunkZ))
                .forEach(claim -> {
                    ServerLevel level = requireLevel(claim.dimensionId());
                    LevelChunk chunk = requireLoadedChunk(level, claim);
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (isSupportedMillstone(blockEntity)) {
                            BlockPos position = blockEntity.getBlockPos();
                            machines.add(new FacilityBaselineMachine(
                                    CreateMachineKind.MILLSTONE,
                                    new FacilityMachinePosition(
                                            claim.dimensionId(),
                                            position.getX(),
                                            position.getY(),
                                            position.getZ())));
                        }
                    }
                });
        machines.sort(MACHINE_ORDER);
        return List.copyOf(machines);
    }

    private static boolean isSupportedMillstone(BlockEntity blockEntity) {
        return blockEntity != null
                && blockEntity.getClass().getName().equals(
                        CreateMillstoneObservationBridge.MILLSTONE_CLASS)
                && BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock())
                        .equals(MILLSTONE_BLOCK);
    }

    private static List<MachineInventoryChange> captureInventory(
            FacilityAccountingInterfaceBlockEntity accountingInterface) {
        List<MachineInventoryChange> inventory = new ArrayList<>();
        for (int slot = 0; slot < accountingInterface.getContainerSize(); slot++) {
            ItemStack stack = accountingInterface.getItem(slot);
            if (!stack.isEmpty()) {
                ItemStack identity = stack.copyWithCount(1);
                inventory.add(new MachineInventoryChange(
                        slot,
                        new ProductionStack(
                                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                                identity.saveOptional(
                                                accountingInterface.getLevel().registryAccess())
                                        .toString(),
                                stack.getCount())));
            }
        }
        return List.copyOf(inventory);
    }

    private ServerLevel requireLevel(String dimensionId) {
        ResourceLocation location;
        try {
            location = ResourceLocation.parse(dimensionId);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Facility dimension", invalid);
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
        if (level == null) {
            throw new IllegalStateException("Facility dimension is not loaded: " + dimensionId);
        }
        return level;
    }

    private static LevelChunk requireLoadedChunk(
            ServerLevel level, TerritoryClaimPosition claim) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(claim.chunkX(), claim.chunkZ());
        if (chunk == null) {
            throw new IllegalStateException(
                    "Facility chunk is not loaded; baseline capture fails closed");
        }
        return chunk;
    }
}
