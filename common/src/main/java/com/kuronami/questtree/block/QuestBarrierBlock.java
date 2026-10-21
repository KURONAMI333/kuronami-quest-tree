package com.kuronami.questtree.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import java.util.List;

/**
 * Heracles' barrier: a solid-looking gate that lets a player through once its quest is complete or its stage is held.
 * Other entities stay out. The block entity holds the key; a barrier without one stays closed.
 */
public class QuestBarrierBlock extends BaseEntityBlock {

    public static final MapCodec<QuestBarrierBlock> CODEC = simpleCodec(QuestBarrierBlock::new);

    public QuestBarrierBlock(Properties properties) {
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
        return new QuestBarrierBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext ec && ec.getEntity() instanceof Player player
                && level.getBlockEntity(pos) instanceof QuestBarrierBlockEntity be && be.allows(player)) {
            return Shapes.empty();
        }
        return Shapes.block();
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof QuestBarrierBlockEntity be) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                    tag.putString("quest_key", be.key());
                    tag.putString("quest_mode", be.mode());
                    drop.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                }
            }
        }
        return drops;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level.getBlockEntity(pos) instanceof QuestBarrierBlockEntity be)) {
            return;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            be.setGate(data.copyTag().getString("quest_mode"), data.copyTag().getString("quest_key"));
        }
        if (!level.isClientSide) {
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }
}
