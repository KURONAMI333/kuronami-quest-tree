package com.kuronami.questtree.platform;

import com.kuronami.questtree.platform.services.IItemStorageHelper;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

public class FabricItemStorageHelper implements IItemStorageHelper {

    @Override
    public long fluidAmount(ItemStack stack, String spec) {
        if (stack.isEmpty() || spec.isEmpty()) {
            return 0;
        }
        // bucket-like items (water, lava) answer through Fabric's fluid providers
        Storage<FluidVariant> storage = FluidStorage.ITEM.find(stack, ContainerItemContext.withConstant(stack));
        if (storage == null) {
            return 0;
        }
        boolean byTag = spec.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(byTag ? spec.substring(1) : spec);
        if (id == null) {
            return 0;
        }
        TagKey<Fluid> tag = byTag ? TagKey.create(Registries.FLUID, id) : null;
        long droplets = 0;
        for (StorageView<FluidVariant> view : storage) {
            FluidVariant variant = view.getResource();
            if (variant.isBlank() || view.getAmount() <= 0) {
                continue;
            }
            Fluid fluid = variant.getFluid();
            boolean match = byTag ? fluid.is(tag) : BuiltInRegistries.FLUID.getKey(fluid).equals(id);
            if (match) {
                droplets += view.getAmount();
            }
        }
        return droplets / (FluidConstants.BUCKET / 1000L);
    }

    @Override
    public long energyStored(ItemStack stack) {
        // Fabric has no energy API in the base API; energy tasks stay at 0 on this loader
        return 0;
    }
}
