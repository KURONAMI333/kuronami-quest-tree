package com.kuronami.questtree.block;

import com.kuronami.questtree.giver.GiverTarget;
import com.kuronami.questtree.server.GiverService;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * A block that opens one quest or chapter, so a pack can put a quest on a wall, in a shop, or at the end of a corridor.
 * The target is stored in the block entity and copied from the item that placed it; the screen's own buttons submit
 * tasks. The server reads the target and asks the client to open it, so the block follows the same hidden and locked
 * rules as every other way into the book (2026-10-22, G3).
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
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof QuestTaskBlockEntity be) {
            GiverTarget target = be.target();
            for (ItemStack drop : drops) {
                if (drop.is(asItem()) && target != null) {
                    drop.set(DataComponents.CUSTOM_DATA, CustomData.of(keyTag(
                            target.chapter() ? QuestNodeKey.CHAPTER_KEY : QuestNodeKey.KEY, target.id())));
                }
            }
        }
        return drops;
    }

    private static net.minecraft.nbt.CompoundTag keyTag(String name, String value) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString(name, value);
        return tag;
    }

    /** Opens what this block holds for the player who clicked it. */
    private static void openFor(Level level, BlockPos pos, Player player) {
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) {
            return;
        }
        GiverTarget target = level.getBlockEntity(pos) instanceof QuestTaskBlockEntity be ? be.target() : null;
        if (target == null) {
            GiverService.openDefault(sp);
        } else {
            GiverService.openTarget(sp, target);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        openFor(level, pos, player);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        openFor(level, pos, player);
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
            var tag = data.copyTag();
            String chapter = tag.getString(QuestNodeKey.CHAPTER_KEY);
            if (!chapter.isEmpty()) {
                be.setTarget(GiverTarget.chapter(chapter));
            } else {
                be.setNode(QuestNodeKey.read(tag::contains, tag::getString));
            }
        }
        if (!level.isClientSide) {
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }
}
