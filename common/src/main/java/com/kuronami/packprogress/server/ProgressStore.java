package com.kuronami.packprogress.server;

import com.google.gson.JsonParser;
import com.kuronami.packprogress.Constants;
import com.kuronami.packprogress.progress.PlayerProgress;
import com.kuronami.packprogress.progress.ProgressJson;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * All players' progress, saved with the world (data/pack_progress.dat). Each player is stored as one JSON string so
 * the format can grow without NBT migrations.
 */
public final class ProgressStore extends SavedData {

    private static final String NAME = Constants.MOD_ID;
    private final Map<UUID, PlayerProgress> players = new HashMap<>();

    public static ProgressStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ProgressStore::new, ProgressStore::load, null), NAME);
    }

    public PlayerProgress of(UUID player) {
        return players.computeIfAbsent(player, k -> new PlayerProgress());
    }

    private static ProgressStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ProgressStore s = new ProgressStore();
        CompoundTag all = tag.getCompound("players");
        for (String key : all.getAllKeys()) {
            try {
                s.players.put(UUID.fromString(key), ProgressJson.read(JsonParser.parseString(all.getString(key)).getAsJsonObject()));
            } catch (RuntimeException ex) {
                Constants.LOG.error("Could not read progress of {}: {}", key, ex.toString());
            }
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        players.forEach((id, p) -> all.putString(id.toString(), ProgressJson.write(p).toString()));
        tag.put("players", all);
        return tag;
    }
}
