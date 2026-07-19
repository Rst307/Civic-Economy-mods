package org.civiceconomy.platform.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

public final class FacilityAccountingInterfaceBlockEntity
        extends RandomizableContainerBlockEntity {
    public static final int SLOT_COUNT = 27;

    private NonNullList<ItemStack> items =
            NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private List<ItemStack> lastObservedInventory = List.of();

    public FacilityAccountingInterfaceBlockEntity(BlockPos position, BlockState state) {
        super(CivicContent.FACILITY_ACCOUNTING_INTERFACE_BLOCK_ENTITY.get(), position, state);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items, registries);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items, registries);
        }
    }

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    static void serverTick(
            Level level,
            BlockPos position,
            BlockState state,
            FacilityAccountingInterfaceBlockEntity blockEntity) {
        List<ItemStack> current =
                FacilityAccountingInterfaceObservationBridge.snapshot(blockEntity);
        if (!blockEntity.lastObservedInventory.isEmpty()) {
            FacilityAccountingInterfaceObservationBridge.observe(
                    level,
                    position,
                    blockEntity.lastObservedInventory,
                    current);
        }
        blockEntity.lastObservedInventory = current;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable(
                "container.civiceconomy.facility_accounting_interface");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return ChestMenu.threeRows(containerId, playerInventory, this);
    }
}
