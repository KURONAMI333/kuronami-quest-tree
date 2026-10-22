package com.kuronami.questtree.block;

import com.kuronami.questtree.server.BarrierTeleports;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level.getBlockEntity(pos) instanceof QuestBarrierBlockEntity be)) {
            return;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            be.setGate(data.copyTag().getString("quest_mode"), data.copyTag().getString("quest_key"));
            be.readTeleport(data.copyTag());
        }
        if (!level.isClientSide) {
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    /**
     * A player who is let through steps inside the block; queue the gate's destination for the next tick. Reaching
     * this method does not bypass the collision rule: only a player the barrier already allows gets here.
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof QuestBarrierBlockEntity be
                && be.teleport() != null && be.allows(player)) {
            BarrierTeleports.queue(player, be.teleport(), pos);
        }
    }
}
