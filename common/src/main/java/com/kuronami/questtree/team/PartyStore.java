package com.kuronami.questtree.team;

import com.kuronami.questtree.Constants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * KQT's built-in party, saved with the world ({@code data/kuronami_quest_tree_party.dat}) as one JSON string. Only
 * used when FTB Teams is absent; membership from FTB Teams is read through its API instead.
 */
public final class PartyStore extends SavedData {

    private static final String NAME = Constants.MOD_ID + "_party";
    private PartyData data = new PartyData();

    public static PartyStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PartyStore::new, PartyStore::load, null), NAME);
    }

    public PartyData data() {
        return data;
    }

    private static PartyStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PartyStore store = new PartyStore();
        if (tag.contains("parties")) {
            try {
                store.data = PartyData.parse(tag.getString("parties"));
            } catch (RuntimeException ex) {
                Constants.LOG.error("Could not read parties: {}", ex.toString());
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("parties", data.toJson().toString());
        return tag;
    }
}
