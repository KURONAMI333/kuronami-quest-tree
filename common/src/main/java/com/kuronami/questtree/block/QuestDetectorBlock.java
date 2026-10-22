package com.kuronami.questtree.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.List;

/**
 * A redstone detector that force-progresses a quest or one of its tasks for every player in its radius. The pulse is
 * taken on the rising edge only, like FTB Quests' detector, so a lever left on does not finish the quest again every
 * tick. The target and radius are copied from the item that placed the block.
 */
public class QuestDetectorBlock extends BaseEntityBlock {

    public static final MapCodec<QuestDetectorBlock> CODEC = simpleCodec(QuestDetectorBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public QuestDetectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new QuestDetectorBlockEntity(pos, state);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        if (level.isClientSide) {
            return;
        }
        boolean wasPowered = state.getValue(POWERED);
        if (wasPowered != level.hasNeighborSignal(pos)) {
            level.setBlock(pos, state.setValue(POWERED, !wasPowered), 2);
            if (!wasPowered && level.getBlockEntity(pos) instanceof QuestDetectorBlockEntity detector) {
                detector.pulse(level, pos);
            }
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level.getBlockEntity(pos) instanceof QuestDetectorBlockEntity detector)) {
            return;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            detector.read(data.copyTag());
        }
        if (!level.isClientSide) {
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    /** Breaking the block keeps its target and radius on the dropped item, like the quest board. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof QuestDetectorBlockEntity detector) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem()) && !detector.target().isEmpty()) {
                    drop.set(DataComponents.CUSTOM_DATA, CustomData.of(detector.data()));
                }
            }
        }
        return drops;
    }
}
