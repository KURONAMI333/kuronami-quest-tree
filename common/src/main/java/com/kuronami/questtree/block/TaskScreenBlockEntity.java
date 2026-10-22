package com.kuronami.questtree.block;

import com.kuronami.questtree.server.TaskScreenService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * The core of a task screen (2026-10-22, FTB Quests parity). It remembers the placing player and the linked task; the
 * progress it shows is the owner's current team progress, refreshed from the store so a screen also shows what other
 * members handed in. The value is synced, because the screen is drawn from the block entity.
 */
public class TaskScreenBlockEntity extends BlockEntity implements TaskScreenAccess {

    private String node = "";
    private int taskIndex = -1;
    private UUID owner;
    private long progress;
    private boolean removingAux;

    public TaskScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.taskScreen(), pos, state);
    }

    public String node() {
        return node;
    }

    public int taskIndex() {
        return taskIndex;
    }

    public UUID owner() {
        return owner;
    }

    public long progress() {
        return progress;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
        setChanged();
        sync();
    }

    public void setBinding(String node, int index) {
        this.node = node == null ? "" : node;
        this.taskIndex = index;
        refreshProgress();
        setChanged();
        sync();
    }

    public void clearBinding() {
        setBinding("", -1);
    }

    /** Whether this core is already taking its aux blocks down, so their removal does not destroy it again. */
    public boolean isRemovingAux() {
        return removingAux;
    }

    public void setRemovingAux(boolean removingAux) {
        this.removingAux = removingAux;
    }

    /** Reads the task's current value from the owner's team. Called on a timer and after every insertion. */
    public void refreshProgress() {
        if (!(level instanceof ServerLevel serverLevel) || owner == null) {
            return;
        }
        long now = TaskScreenService.progress(serverLevel.getServer(), owner, node, taskIndex);
        if (now != progress) {
            progress = now;
            sync();
        }
    }

    public void removeAux() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof TaskScreenBlock screen)) {
            return;
        }
        for (BlockPos pos : TaskScreenBlock.screenPositions(worldPosition, screen.getSize(),
                state.getValue(TaskScreenBlock.FACING))) {
            if (!pos.equals(worldPosition) && serverLevel.getBlockState(pos).is(ModBlocks.taskScreenAux())) {
                serverLevel.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    public void serverTick() {
        if (level == null || level.isClientSide() || level.getGameTime() % 20 != 0) {
            return;
        }
        refreshProgress();
    }

    /** The loaders' item capability funnels through here; returns how many items the screen took. */
    public long insertScreenItem(ItemStack stack) {
        return level instanceof ServerLevel serverLevel ? TaskScreenService.insertItem(serverLevel, this, stack) : 0;
    }

    public long itemCapacity(ItemStack stack) {
        return level instanceof ServerLevel serverLevel ? TaskScreenService.itemCapacity(serverLevel, this, stack) : 0;
    }

    public long insertScreenFluid(Fluid fluid, long amount) {
        return level instanceof ServerLevel serverLevel ? TaskScreenService.insertFluid(serverLevel, this, fluid, amount) : 0;
    }

    public long fluidCapacity(Fluid fluid) {
        return level instanceof ServerLevel serverLevel ? TaskScreenService.fluidCapacity(serverLevel, this, fluid) : 0;
    }

    public long insertScreenEnergy(long amount) {
        return level instanceof ServerLevel serverLevel ? TaskScreenService.insertEnergy(serverLevel, this, amount) : 0;
    }

    public long energyCapacity() {
        return level instanceof ServerLevel serverLevel ? TaskScreenService.energyCapacity(serverLevel, this) : 0;
    }

    @Override
    public long screenProgress() {
        return progress;
    }

    private void sync() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!node.isEmpty()) {
            tag.putString("node", node);
        }
        tag.putInt("task", taskIndex);
        tag.putLong("progress", progress);
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        node = tag.getString("node");
        taskIndex = tag.getInt("task");
        progress = tag.getLong("progress");
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }
}
