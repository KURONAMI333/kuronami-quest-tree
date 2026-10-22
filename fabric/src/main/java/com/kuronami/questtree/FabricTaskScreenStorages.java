package com.kuronami.questtree;

import com.kuronami.questtree.block.TaskScreenAccess;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;

import java.util.Iterator;
import java.util.List;

/**
 * Fabric's transfer API view of a task screen's items and fluids (2026-10-22). Insertion is permanent (the item or
 * fluid becomes task progress), so there is nothing to extract. Energy is not part of this API; see
 * {@link FabricEnergyStorages}, which registers Team Reborn Energy when that optional mod is loaded.
 */
final class FabricTaskScreenStorages {

    private FabricTaskScreenStorages() {
    }

    static Storage<ItemVariant> item(TaskScreenAccess screen) {
        return new Storage<>() {
            @Override
            public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
                if (resource.isBlank() || maxAmount <= 0) {
                    return 0;
                }
                ItemStack stack = resource.toStack((int) Math.min(maxAmount, Integer.MAX_VALUE));
                return screen.insertScreenItem(stack);
            }

            @Override
            public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
                return 0;
            }

            @Override
            public Iterator<StorageView<ItemVariant>> iterator() {
                return List.<StorageView<ItemVariant>>of(new ItemView()).iterator();
            }

            private final class ItemView implements StorageView<ItemVariant> {
                @Override
                public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
                    return 0;
                }

                @Override
                public boolean isResourceBlank() {
                    return true;
                }

                @Override
                public ItemVariant getResource() {
                    return ItemVariant.blank();
                }

                @Override
                public long getAmount() {
                    return 0;
                }

                @Override
                public long getCapacity() {
                    return 64;
                }
            }
        };
    }

    static Storage<FluidVariant> fluid(TaskScreenAccess screen) {
        return new Storage<>() {
            @Override
            public long insert(FluidVariant resource, long maxAmount, TransactionContext transaction) {
                if (resource.isBlank() || maxAmount <= 0) {
                    return 0;
                }
                return screen.insertScreenFluid(resource.getFluid(), maxAmount);
            }

            @Override
            public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
                return 0;
            }

            @Override
            public Iterator<StorageView<FluidVariant>> iterator() {
                return List.<StorageView<FluidVariant>>of(new FluidView()).iterator();
            }

            private final class FluidView implements StorageView<FluidVariant> {
                @Override
                public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
                    return 0;
                }

                @Override
                public boolean isResourceBlank() {
                    return true;
                }

                @Override
                public FluidVariant getResource() {
                    return FluidVariant.blank();
                }

                @Override
                public long getAmount() {
                    return 0;
                }

                @Override
                public long getCapacity() {
                    return 1_000_000;
                }
            }
        };
    }
}
