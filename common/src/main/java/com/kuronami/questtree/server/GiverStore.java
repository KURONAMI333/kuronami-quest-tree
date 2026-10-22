package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.data.SavedDataFiles;
import com.kuronami.questtree.giver.GiverLinks;
import com.kuronami.questtree.giver.GiverTarget;
import com.kuronami.questtree.progress.JsonTags;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.File;
import java.util.UUID;

/**
 * The world's quest givers: entity UUID to quest or chapter (2026-10-22, G3). It is its own file
 * ({@code data/kuronami_quest_tree_givers.dat}) so a broken link list can never take player progress with it, and it
 * is written through {@link SavedDataFiles}' safe path (temporary file, backup, atomic move).
 * <p>
 * Block givers keep their target in the block entity's own NBT, next to the block it belongs to.
 */
public final class GiverStore extends SavedData {

    public static final String NAME = Constants.MOD_ID + "_givers";
    private static final int VERSION = 1;

    private GiverLinks links = new GiverLinks();

    public static GiverStore get(MinecraftServer server) {
        return SavedDataFiles.get(server, NAME, GiverStore::load, GiverStore::new);
    }

    public GiverTarget target(UUID entity) {
        return links.get(entity);
    }

    public void link(UUID entity, GiverTarget target) {
        links.set(entity, target);
        setDirty();
    }

    /** Returns whether a link was there. */
    public boolean unlink(UUID entity) {
        boolean removed = links.clear(entity);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** The saved links, for a test or a migration. */
    public GiverLinks links() {
        return links;
    }

    static GiverStore load(CompoundTag tag, HolderLookup.Provider registries) {
        GiverStore store = new GiverStore();
        if (!tag.contains("entities")) {
            return store;
        }
        JsonElement entities = JsonTags.fromTag(tag.get("entities"));
        if (entities.isJsonObject()) {
            JsonObject json = entities.getAsJsonObject();
            store.links = GiverLinks.fromJson(json);
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", VERSION);
        tag.put("entities", JsonTags.toTag(links.toJson()));
        return tag;
    }

    @Override
    public void save(File file, HolderLookup.Provider registries) {
        SavedDataFiles.saveAtomically(this, file, registries);
    }
}
