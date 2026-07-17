package org.civiceconomy.platform.neoforge;

import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class FacilityAccountingInterfaceBlock extends BaseEntityBlock {
    public static final MapCodec<FacilityAccountingInterfaceBlock> CODEC =
            simpleCodec(FacilityAccountingInterfaceBlock::new);

    public FacilityAccountingInterfaceBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos position,
            Player player,
            BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (blockEntity instanceof FacilityAccountingInterfaceBlockEntity accountingInterface) {
            player.openMenu(accountingInterface);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(
            BlockState state,
            Level level,
            BlockPos position,
            BlockState newState,
            boolean isMoving) {
        Containers.dropContentsOnDestroy(state, newState, level, position);
        super.onRemove(state, level, position, newState, isMoving);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new FacilityAccountingInterfaceBlockEntity(position, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> blockEntityType) {
        return level.isClientSide
                ? null
                : createTickerHelper(
                        blockEntityType,
                        CivicContent.FACILITY_ACCOUNTING_INTERFACE_BLOCK_ENTITY.get(),
                        FacilityAccountingInterfaceBlockEntity::serverTick);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
