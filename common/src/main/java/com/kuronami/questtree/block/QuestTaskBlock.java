package com.kuronami.questtree.block;

import com.kuronami.questtree.platform.Services;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A block that opens one quest, so a pack can put a quest on a wall, in a shop, or at the end of a corridor. The node
 * key is stored in the block entity and copied from the item that placed it; the screen's own buttons submit tasks.
 */
public class QuestTaskBlock extends BaseEntityBlock {

    public static final MapCodec<QuestTaskBlock> CODEC = simpleCodec(QuestTaskBlock::new);

    public QuestTaskBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new QuestTaskBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            String node = level.getBlockEntity(pos) instanceof QuestTaskBlockEntity be ? be.node() : "";
            Services.PLATFORM.openProgressionScreen(node);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            String node = level.getBlockEntity(pos) instanceof QuestTaskBlockEntity be ? be.node() : "";
            Services.PLATFORM.openProgressionScreen(node);
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level.getBlockEntity(pos) instanceof QuestTaskBlockEntity be)) {
            return;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            be.setNode(data.copyTag().getString("quest_node"));
        }
        if (!level.isClientSide) {
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }
}
