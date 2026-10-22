package com.kuronami.questtree.block;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

/**
 * The insertion side of a task screen, shared by the core and its aux blocks so a loader capability can register the
 * same handler for both (2026-10-22). Everything is server-only; the loaders call these from the block entity.
 */
public interface TaskScreenAccess {

    long insertScreenItem(ItemStack stack);

    long itemCapacity(ItemStack stack);

    long insertScreenFluid(Fluid fluid, long amount);

    long fluidCapacity(Fluid fluid);

    long insertScreenEnergy(long amount);

    long energyCapacity();

    long screenProgress();
}
