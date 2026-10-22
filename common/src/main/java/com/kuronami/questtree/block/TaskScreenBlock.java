package com.kuronami.questtree.block;

import com.kuronami.questtree.item.TaskScreenConfiguratorItem;
import com.kuronami.questtree.server.TaskScreenService;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A wall screen that shows one task and takes what the task wants (2026-10-22, FTB Quests parity). The block placed by
 * the player is the core; the rest of the 3x3, 5x5 or 7x7 face is filled with aux blocks that share the core. Breaking
 * any part takes the whole screen down, and breaking an aux drops the core's item, like FTB Quests' screens.
 */
public class TaskScreenBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public static final MapCodec<TaskScreenBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(),
            Codec.INT.fieldOf("size").forGetter(TaskScreenBlock::getSize)
    ).apply(instance, TaskScreenBlock::new));

    private final int size;

    public TaskScreenBlock(Properties properties, int size) {
        super(properties);
        this.size = size;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    public int getSize() {
        return size;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TaskScreenBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return size == 1 ? Shapes.block() : super.getCollisionShape(state, level, pos, context);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (!validatePlaceable(context)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    private boolean validatePlaceable(BlockPlaceContext context) {
        if (size == 1) {
            return true;
        }
        Direction facing = context.getHorizontalDirection().getOpposite();
        for (BlockPos pos : screenPositions(context.getClickedPos(), size, facing)) {
            if (!context.getLevel().getBlockState(pos).canBeReplaced(context)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level.getBlockEntity(pos) instanceof TaskScreenBlockEntity core)) {
            return;
        }
        if (placer instanceof ServerPlayer player) {
            core.setOwner(player.getUUID());
        }
        Direction facing = state.getValue(FACING);
        BlockState auxState = ModBlocks.taskScreenAux().defaultBlockState().setValue(FACING, facing);
        for (BlockPos auxPos : screenPositions(pos, size, facing)) {
            if (auxPos.equals(pos)) {
                continue;
            }
            level.setBlock(auxPos, auxState, Block.UPDATE_ALL);
            if (level.getBlockEntity(auxPos) instanceof TaskScreenAuxBlockEntity aux) {
                aux.setCore(core);
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof TaskScreenBlockEntity core) {
                core.setRemovingAux(true);
                core.removeAux();
            } else if (level.getBlockEntity(pos) instanceof TaskScreenAuxBlockEntity aux) {
                aux.setRemoving(true);
                aux.core().ifPresent(core -> {
                    if (!core.isRemovingAux()) {
                        core.setRemovingAux(true);
                        core.removeAux();
                        level.destroyBlock(core.getBlockPos(), true);
                    }
                });
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof TaskScreenAuxBlockEntity aux && aux.isRemoving()
                ? 0.0F : super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return state.getBlock() instanceof Aux ? List.of() : super.getDrops(state, params);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (tickLevel, pos, tickState, be) -> {
            if (be instanceof TaskScreenBlockEntity core) {
                core.serverTick();
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            TaskScreenService.requestOpen(serverPlayer, pos);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        TaskScreenBlockEntity core = TaskScreenService.coreAt(level, pos);
        if (core == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof TaskScreenConfiguratorItem) {
            if (level.isClientSide) {
                return ItemInteractionResult.SUCCESS;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                TaskScreenService.requestOpen(serverPlayer, pos);
            }
            return ItemInteractionResult.CONSUME;
        }
        if (level.isClientSide) {
            return core.node().isEmpty() ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    : ItemInteractionResult.SUCCESS;
        }
        long inserted = core.insertScreenItem(stack);
        if (inserted <= 0) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!player.isCreative()) {
            stack.shrink((int) inserted);
        }
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.2F);
        return ItemInteractionResult.SUCCESS;
    }

    /** Every block of a screen, core included, from the core and the facing it was placed with. */
    public static Iterable<BlockPos> screenPositions(BlockPos core, int size, Direction facing) {
        if (size <= 1) {
            return List.of(core);
        }
        int half = size / 2;
        Direction width = facing.getCounterClockWise();
        BlockPos first = core.offset(-half * width.getStepX(), 0, -half * width.getStepZ());
        BlockPos last = core.offset(half * width.getStepX(), size - 1, half * width.getStepZ());
        return BlockPos.betweenClosed(first, last);
    }

    public static AABB bounds(BlockPos core, int size, Direction facing) {
        int half = Math.max(0, size / 2);
        Direction width = facing.getCounterClockWise();
        boolean wideX = width.getStepX() != 0;
        double minX = core.getX() - (wideX ? half : 0);
        double maxX = core.getX() + (wideX ? half + 1 : 1);
        double minZ = core.getZ() - (wideX ? 0 : half);
        double maxZ = core.getZ() + (wideX ? 1 : half + 1);
        return new AABB(minX, core.getY(), minZ, maxX, core.getY() + Math.max(1, size), maxZ);
    }

    public static class Aux extends TaskScreenBlock {

        public static final MapCodec<Aux> CODEC = simpleCodec(Aux::new);

        public Aux(Properties properties) {
            super(properties, 0);
        }

        @Override
        protected MapCodec<Aux> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new TaskScreenAuxBlockEntity(pos, state);
        }
    }
}
