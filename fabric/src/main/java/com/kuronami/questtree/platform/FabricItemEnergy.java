package com.kuronami.questtree.platform;

import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.minecraft.world.item.ItemStack;
import team.reborn.energy.api.EnergyStorage;

/**
 * Energy inside one item stack through Team Reborn Energy (2026-10-22, the same library FTB Quests' Fabric build
 * bundles). Only called when the mod is loaded; this class is never touched otherwise, so Team Reborn Energy stays an
 * optional dependency.
 */
final class FabricItemEnergy {

    private FabricItemEnergy() {
    }

    static long stored(ItemStack stack) {
        EnergyStorage storage = EnergyStorage.ITEM.find(stack, ContainerItemContext.withConstant(stack));
        return storage == null ? 0 : storage.getAmount();
    }
}
