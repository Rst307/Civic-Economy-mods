package org.civiceconomy.platform.neoforge;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.civiceconomy.CivicEconomy;

public final class CivicContent {
    private static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CivicEconomy.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CivicEconomy.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CivicEconomy.MOD_ID);

    public static final DeferredBlock<FacilityAccountingInterfaceBlock>
            FACILITY_ACCOUNTING_INTERFACE = BLOCKS.registerBlock(
                    "facility_accounting_interface",
                    FacilityAccountingInterfaceBlock::new,
                    BlockBehaviour.Properties.of()
                            .strength(3.5F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> FACILITY_ACCOUNTING_INTERFACE_ITEM =
            ITEMS.registerSimpleBlockItem(FACILITY_ACCOUNTING_INTERFACE);
    public static final DeferredHolder<BlockEntityType<?>,
                    BlockEntityType<FacilityAccountingInterfaceBlockEntity>>
            FACILITY_ACCOUNTING_INTERFACE_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "facility_accounting_interface",
                    () -> BlockEntityType.Builder.of(
                                    FacilityAccountingInterfaceBlockEntity::new,
                                    FACILITY_ACCOUNTING_INTERFACE.get())
                            .build(null));

    private CivicContent() {}

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(CivicContent::registerCapabilities);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                FACILITY_ACCOUNTING_INTERFACE_BLOCK_ENTITY.get(),
                (blockEntity, direction) -> new InvWrapper(blockEntity));
    }
}
