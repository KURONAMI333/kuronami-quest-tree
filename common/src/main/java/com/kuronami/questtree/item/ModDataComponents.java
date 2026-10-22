package com.kuronami.questtree.item;

import net.minecraft.core.component.DataComponentType;

import java.util.function.Supplier;

/** Loader-filled holder for the loot crate's table id (the only thing a crate stack carries). */
public final class ModDataComponents {

    private static Supplier<DataComponentType<String>> lootCrate;

    private ModDataComponents() {
    }

    public static DataComponentType<String> lootCrate() {
        return lootCrate == null ? null : lootCrate.get();
    }

    public static void setLootCrate(DataComponentType<String> type) {
        lootCrate = () -> type;
    }

    /** Registers the component lazily: a NeoForge {@code DeferredHolder} cannot be read during mod construction. */
    public static void setLootCrate(Supplier<DataComponentType<String>> type) {
        lootCrate = type;
    }
}
