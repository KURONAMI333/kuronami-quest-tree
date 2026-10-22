package com.kuronami.questtree;

import com.kuronami.questtree.block.TaskScreenAccess;
import com.kuronami.questtree.block.TaskScreenAuxBlockEntity;
import com.kuronami.questtree.block.TaskScreenBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.level.block.entity.BlockEntityType;
import team.reborn.energy.api.EnergyStorage;

/**
 * Team Reborn Energy's side of a task screen (2026-10-22, matching FTB Quests' Fabric task screen): a cable or a
 * battery pushes FE into the core or its aux block and it becomes progress on an energy task. Team Reborn Energy is
 * optional, so {@link #register} is only called when {@code team_reborn_energy} is loaded; this class is never
 * touched otherwise.
 */
final class FabricEnergyStorages {

    private FabricEnergyStorages() {
    }

    static void register(BlockEntityType<TaskScreenBlockEntity> core, BlockEntityType<TaskScreenAuxBlockEntity> aux) {
        EnergyStorage.SIDED.registerForBlockEntity((screen, side) -> new ScreenEnergyStorage(screen), core);
        EnergyStorage.SIDED.registerForBlockEntity((screen, side) -> new ScreenEnergyStorage(screen), aux);
    }

    /** Insertion is permanent (the FE becomes task progress), so there is nothing to take back out. */
    private record ScreenEnergyStorage(TaskScreenAccess screen) implements EnergyStorage {

        @Override
        public long insert(long maxAmount, TransactionContext transaction) {
            if (maxAmount <= 0) {
                return 0;
            }
            long take = Math.min(maxAmount, screen.energyCapacity());
            if (take <= 0) {
                return 0;
            }
            return screen.insertScreenEnergy(take);
        }

        @Override
        public long extract(long maxAmount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public boolean supportsExtraction() {
            return false;
        }

        @Override
        public long getAmount() {
            return screen.screenProgress();
        }

        @Override
        public long getCapacity() {
            return screen.screenProgress() + screen.energyCapacity();
        }
    }
}
