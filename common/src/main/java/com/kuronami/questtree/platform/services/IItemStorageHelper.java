package com.kuronami.questtree.platform.services;

import net.minecraft.world.item.ItemStack;

/**
 * Loader-specific read-outs of the fluids and the energy held inside a single item stack. Loader APIs live behind
 * this service so common code never touches NeoForge's capabilities or Fabric's transfer API.
 */
public interface IItemStorageHelper {

    /**
     * Amount of the matching fluid in mB (1 bucket = 1000 mB) held by this stack, 0 when it holds none or the fluid
     * does not match. {@code spec} is an id ("minecraft:water") or a tag ("#c:water").
     */
    long fluidAmount(ItemStack stack, String spec);

    /** Energy in FE stored in this stack; 0 when it stores none, or when the loader has no energy API. */
    long energyStored(ItemStack stack);
}
