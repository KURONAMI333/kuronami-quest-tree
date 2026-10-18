package com.kuronami.questtree.platform;

import com.kuronami.questtree.platform.services.IItemStorageHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

public class NeoForgeItemStorageHelper implements IItemStorageHelper {

    @Override
    public long fluidAmount(ItemStack stack, String spec) {
        if (stack.isEmpty() || spec.isEmpty()) {
            return 0;
        }
        // vanilla buckets (water, lava, milk) answer through the same capability, so they need no special case
        IFluidHandlerItem handler = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (handler == null) {
            return 0;
        }
        boolean byTag = spec.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(byTag ? spec.substring(1) : spec);
        if (id == null) {
            return 0;
        }
        TagKey<Fluid> tag = byTag ? TagKey.create(Registries.FLUID, id) : null;
        long total = 0;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack fluid = handler.getFluidInTank(tank);
            if (fluid.isEmpty()) {
                continue;
            }
            boolean match = byTag ? fluid.getFluid().is(tag) : BuiltInRegistries.FLUID.getKey(fluid.getFluid()).equals(id);
            if (match) {
                total += fluid.getAmount();
            }
        }
        return total;
    }

    @Override
    public long energyStored(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        return storage == null ? 0 : storage.getEnergyStored();
    }
}
