package com.kuronami.questtreegametest;

import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Dev-only companion mod; the GameTests are found through {@code @GameTestHolder}. */
@Mod("kuronami_quest_tree_gametest")
public final class QuestTreeGameTestMod {

    public QuestTreeGameTestMod(IEventBus bus) {
        bus.addListener(this::registerEnergy);
    }

    /**
     * Gives a redstone block a full 1000 FE store so the "energy" task has something real to count (nothing in the
     * game outside mods stores energy, so the test would otherwise only ever see 0). Dev only: the companion mod is
     * never shipped.
     */
    private void registerEnergy(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.EnergyStorage.ITEM, (stack, ctx) -> new IEnergyStorage() {
            @Override
            public int receiveEnergy(int toReceive, boolean simulate) {
                return 0;
            }

            @Override
            public int extractEnergy(int toExtract, boolean simulate) {
                return 0;
            }

            @Override
            public int getEnergyStored() {
                return 1000;
            }

            @Override
            public int getMaxEnergyStored() {
                return 1000;
            }

            @Override
            public boolean canExtract() {
                return false;
            }

            @Override
            public boolean canReceive() {
                return false;
            }
        }, Items.REDSTONE_BLOCK);
    }
}
