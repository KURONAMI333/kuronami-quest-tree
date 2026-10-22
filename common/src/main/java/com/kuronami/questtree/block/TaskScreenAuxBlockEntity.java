package com.kuronami.questtree.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

import java.lang.ref.WeakReference;
import java.util.Optional;

/**
 * A non-core part of a task screen (2026-10-22). It owns no progress: it points at the core, and every capability
 * delegates there, so a pipe or a hopper can connect to any part of a 3x3, 5x5 or 7x7 screen.
 */
public class TaskScreenAuxBlockEntity extends BlockEntity implements TaskScreenAccess {

    private BlockPos corePos;
    private WeakReference<TaskScreenBlockEntity> core;
    private boolean removing;

    public TaskScreenAuxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.taskScreenAux(), pos, state);
    }

    public Optional<TaskScreenBlockEntity> core() {
        if (core == null || core.get() == null) {
            Level level = getLevel();
            if (corePos != null && level != null && level.getBlockEntity(corePos) instanceof TaskScreenBlockEntity found) {
                core = new WeakReference<>(found);
            }
        }
        return core == null ? Optional.empty() : Optional.ofNullable(core.get());
    }

    public void setCore(TaskScreenBlockEntity core) {
        this.core = new WeakReference<>(core);
        this.corePos = core.getBlockPos();
        setChanged();
    }

    public BlockPos corePos() {
        return corePos;
    }

    /** Guards the cascade: while this aux is being removed, its core must not be destroyed a second time. */
    public boolean isRemoving() {
        return removing;
    }

    public void setRemoving(boolean removing) {
        this.removing = removing;
    }

    public long insertScreenItem(ItemStack stack) {
        return core().map(c -> c.insertScreenItem(stack)).orElse(0L);
    }

    public long itemCapacity(ItemStack stack) {
        return core().map(c -> c.itemCapacity(stack)).orElse(0L);
    }

    public long insertScreenFluid(Fluid fluid, long amount) {
        return core().map(c -> c.insertScreenFluid(fluid, amount)).orElse(0L);
    }

    public long fluidCapacity(Fluid fluid) {
        return core().map(c -> c.fluidCapacity(fluid)).orElse(0L);
    }

    public long insertScreenEnergy(long amount) {
        return core().map(c -> c.insertScreenEnergy(amount)).orElse(0L);
    }

    public long energyCapacity() {
        return core().map(TaskScreenBlockEntity::energyCapacity).orElse(0L);
    }

    @Override
    public long screenProgress() {
        return core().map(TaskScreenBlockEntity::progress).orElse(0L);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (corePos != null) {
            tag.putLong("core", corePos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        corePos = tag.contains("core") ? BlockPos.of(tag.getLong("core")) : null;
        core = null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }
}
