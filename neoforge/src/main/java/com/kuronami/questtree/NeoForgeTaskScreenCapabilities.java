package com.kuronami.questtree;

import com.kuronami.questtree.block.TaskScreenAccess;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** The NeoForge capabilities of a task screen (2026-10-22); both the core and its aux blocks expose the same one. */
final class NeoForgeTaskScreenCapabilities {

    private NeoForgeTaskScreenCapabilities() {
    }

    static IItemHandler item(TaskScreenAccess screen) {
        return new ScreenItemHandler(screen);
    }

    static IFluidHandler fluid(TaskScreenAccess screen) {
        return new ScreenFluidHandler(screen);
    }

    static IEnergyStorage energy(TaskScreenAccess screen) {
        return new ScreenEnergyHandler(screen);
    }

    private record ScreenItemHandler(TaskScreenAccess screen) implements IItemHandler {

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 0 || stack.isEmpty()) {
                return stack;
            }
            long accepted = simulate ? Math.min(stack.getCount(), screen.itemCapacity(stack))
                    : screen.insertScreenItem(stack);
            if (accepted <= 0) {
                return stack;
            }
            ItemStack rest = stack.copy();
            rest.setCount(stack.getCount() - (int) accepted);
            return rest;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && screen.itemCapacity(stack) > 0;
        }
    }

    private record ScreenFluidHandler(TaskScreenAccess screen) implements IFluidHandler {

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return 1_000_000;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return tank == 0 && screen.fluidCapacity(stack.getFluid()) > 0;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) {
                return 0;
            }
            int take = (int) Math.min(resource.getAmount(), screen.fluidCapacity(resource.getFluid()));
            if (take <= 0) {
                return 0;
            }
            if (action.execute()) {
                return (int) screen.insertScreenFluid(resource.getFluid(), take);
            }
            return take;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    private record ScreenEnergyHandler(TaskScreenAccess screen) implements IEnergyStorage {

        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            if (toReceive <= 0) {
                return 0;
            }
            long take = Math.min(toReceive, screen.energyCapacity());
            if (take <= 0) {
                return 0;
            }
            return simulate ? (int) take : (int) screen.insertScreenEnergy(take);
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, screen.screenProgress());
        }

        @Override
        public int getMaxEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, screen.screenProgress() + screen.energyCapacity());
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return screen.energyCapacity() > 0;
        }
    }
}
