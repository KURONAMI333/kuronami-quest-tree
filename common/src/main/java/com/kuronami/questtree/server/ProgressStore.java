package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.progress.PlayerPart;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.TeamProgress;
import com.kuronami.questtree.progress.TeamStoreData;
import com.kuronami.questtree.team.TeamResolver;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/**
 * All progress, saved with the world ({@code data/kuronami_quest_tree.dat}). Version 2 keys one record per team id
 * (a solo player's id is their UUID); version 1 held one record per player and is upgraded on first load, with a
 * backup of the old file next to it ({@code .dat.v1.bak}).
 */
public final class ProgressStore extends SavedData {

    private static final String NAME = Constants.MOD_ID;
    private TeamStoreData data = new TeamStoreData();
    /** The file was version 1 when it was read: save it as version 2 and leave a backup of the old one. */
    private boolean loadedLegacy;
    /** The model the old-save paid-skill migration already ran for; a reload runs it again over that model. */
    private com.kuronami.questtree.data.ProgressModel paidSkillModel;

    public static ProgressStore get(MinecraftServer server) {
        ProgressStore store = server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ProgressStore::new, ProgressStore::load, null), NAME);
        if (store.loadedLegacy) {
            store.loadedLegacy = false;
            store.backup(server);
            store.setDirty();
        }
        return store;
    }

    public TeamProgress team(UUID id) {
        return data.team(id);
    }

    /**
     * Fills the paid-skill record of old saves, once per team: a team loaded without the {@code paid_skill_rewards}
     * key is migrated and the key is written by the next save, so a new save is never filled a second time
     * (2026-10-22 review). A save from before the record existed has no way to say which reward was paid, so its taken
     * skill nodes are filled as fully paid.
     */
    public void migratePaidSkillRewards(com.kuronami.questtree.data.ProgressModel model) {
        if (model == paidSkillModel || model == null || model.nodes.isEmpty()) {
            // an empty pack is not proof that no skill was taken; do not remember it as migrated
            return;
        }
        paidSkillModel = model;
        if (data.migratePaidSkillRewards(model)) {
            setDirty();
        }
    }

    /** Routes saved values if the data model changed which nodes are personal. */
    public boolean migratePersonalNodes(java.util.Set<String> current, boolean modelEmpty) {
        if (data.migratePersonalNodes(current, modelEmpty)) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Moves claim records when an editor reorder/insert/delete changed reward indices (2026-10-22 release review).
     * Keys are {@code <node>#<index>} for claims and {@code <node>#<rank>#<index>} for pending, roll and skill records;
     * a record whose reward was deleted is dropped.
     */
    /** Moves saved claim records for a reward remap; true when one changed, so the sender knows to resend progress. */
    public boolean remapRewardIndices(Map<String, Map<Integer, Integer>> remaps) {
        if (remaps.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (TeamProgress team : data.teams.values()) {
            changed |= com.kuronami.questtree.progress.RewardIndexRemap.applyTo(team.shared.claimedRewards, remaps);
            changed |= remapPending(team.shared, remaps);
            changed |= com.kuronami.questtree.progress.RewardIndexRemap.applyTo(team.teamClaimedRewards, remaps);
            changed |= com.kuronami.questtree.progress.RewardIndexRemap.applyTo(team.paidSkillRewards, remaps);
            for (PlayerPart part : team.players.values()) {
                changed |= com.kuronami.questtree.progress.RewardIndexRemap.applyTo(part.claimedRewards, remaps);
                changed |= remapPending(part, remaps);
            }
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    private static boolean remapPending(PlayerPart part,
                                        Map<String, Map<Integer, Integer>> remaps) {
        boolean changed = remapKeys(part.pendingRewards, remaps);
        changed |= remapKeys(part.rewardRolls, remaps);
        return changed;
    }

    private static <V> boolean remapKeys(Map<String, V> map,
                                         Map<String, Map<Integer, Integer>> remaps) {
        if (map.isEmpty()) {
            return false;
        }
        Map<String, V> moved = new java.util.LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, V> e : map.entrySet()) {
            String key = com.kuronami.questtree.progress.RewardIndexRemap.apply(e.getKey(), remaps);
            if (key == null) {
                changed = true;
                continue;
            }
            changed |= !key.equals(e.getKey());
            moved.put(key, e.getValue());
        }
        if (changed) {
            map.clear();
            map.putAll(moved);
        }
        return changed;
    }

    private static boolean remapPending(PlayerProgress p,
                                        Map<String, Map<Integer, Integer>> remaps) {
        boolean changed = remapKeys(p.pendingRewards, remaps);
        changed |= remapKeys(p.rewardRolls, remaps);
        return changed;
    }

    /**
     * The record the player currently acts on. A change of team is detected here: joining folds the player's own
     * record into the team, leaving copies the team back to a new solo record.
     */
    public PlayerProgress view(MinecraftServer server, UUID player) {
        UUID teamId = TeamResolver.teamId(server, player);
        if (data.changedTeam(player, teamId, ProgressStore::settledTeamRewards)) {
            setDirty();
        }
        return data.team(teamId).view(player);
    }

    /**
     * A read-only view for a path that must not change teams (barrier collision): no fold, no migration. The answer
     * can be one team change behind for a few seconds until the ordinary {@link #view} runs.
     */
    public PlayerProgress viewReadOnly(MinecraftServer server, UUID player) {
        return data.team(TeamResolver.teamId(server, player)).view(player);
    }

    /**
     * Takes a player out of their old team now, online or not: the copy is made from the team's progress at this
     * moment, not at their next login. Returns the team they left, or null when nothing changed.
     */
    public UUID settleLeave(UUID player) {
        UUID old = data.teamOf(player);
        if (data.changedTeam(player, player, ProgressStore::settledTeamRewards)) {
            setDirty();
            return old;
        }
        return null;
    }

    /** Team rewards ({@code "team": true}) of the nodes this team completed. */
    private static java.util.Set<String> settledTeamRewards(TeamProgress team, UUID player) {
        var nodes = ProgressService.rules().model().nodes;
        java.util.Set<String> out = new java.util.HashSet<>();
        java.util.Set<String> completed = new java.util.HashSet<>(team.completedFor(player));
        for (String key : completed) {
            var n = nodes.get(key);
            if (n == null) {
                continue;
            }
            for (int i = 0; i < n.rewards.size(); i++) {
                if (com.kuronami.questtree.data.ProgressModel.boolOf(n.rewards.get(i), "team")) {
                    out.add(key + "#" + i);
                }
            }
        }
        return out;
    }

    /**
     * A deep copy of every record (through the saved form), so a migration dry run can be computed and thrown away.
     */
    public TeamStoreData copyData() {
        return TeamStoreData.fromJson(data.toJson());
    }

    /** Swaps in the result of a migration run on {@link #copyData()}. Views are made per call, so none go stale. */
    public void replaceData(TeamStoreData replacement) {
        data = replacement;
        setDirty();
    }

    public UUID teamIdOf(UUID player) {
        return data.teamOf(player);
    }

    private void backup(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat");
        Path backup = file.resolveSibling(NAME + ".dat.v1.bak");
        if (!Files.isRegularFile(file)) {
            return;
        }
        if (Files.exists(backup)) {
            Constants.LOG.info("Keeping the existing progress backup {}", backup);
            return;
        }
        try {
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
            Constants.LOG.info("Backed up the version 1 progress file to {}", backup);
        } catch (IOException ex) {
            Constants.LOG.warn("Could not back up {}: {}", file, ex.toString());
        }
    }

    static ProgressStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ProgressStore store = new ProgressStore();
        if (tag.contains("version") && tag.getInt("version") >= TeamStoreData.VERSION && tag.contains("teams")) {
            JsonObject root = new JsonObject();
            root.addProperty("version", tag.getInt("version"));
            JsonObject teams = new JsonObject();
            CompoundTag saved = tag.getCompound("teams");
            for (String key : saved.getAllKeys()) {
                try {
                    // A compound is the current form; a string is the whole record in one writeUTF, which old saves
                    // (and any save made before this fix) used, so both must load (2026-10-22 release review).
                    net.minecraft.nbt.Tag record = saved.get(key);
                    JsonElement parsed = record instanceof CompoundTag compound
                            ? com.kuronami.questtree.progress.JsonTags.fromTag(compound)
                            : JsonParser.parseString(saved.getString(key));
                    teams.add(key, parsed);
                } catch (RuntimeException ex) {
                    Constants.LOG.error("Could not read team {}: {}", key, ex.toString());
                }
            }
            root.add("teams", teams);
            JsonObject owners = new JsonObject();
            CompoundTag savedOwners = tag.getCompound("owners");
            for (String key : savedOwners.getAllKeys()) {
                owners.addProperty(key, savedOwners.getString(key));
            }
            root.add("owners", owners);
            JsonObject migrations = new JsonObject();
            CompoundTag savedMigrations = tag.getCompound("migrations");
            for (String key : savedMigrations.getAllKeys()) {
                migrations.addProperty(key, savedMigrations.getString(key));
            }
            root.add("migrations", migrations);
            // personal_nodes was written to the JSON form but not to NBT, so a restart forgot the routing set and
            // migrated personal/shared values again on every start (2026-10-22 release review).
            if (tag.contains("personal_nodes")) {
                root.add("personal_nodes", com.kuronami.questtree.progress.JsonTags.fromTag(tag.get("personal_nodes")));
            }
            store.data = TeamStoreData.fromJson(root);
            int droppedSkillPending = 0;
            for (TeamProgress team : store.data.teams.values()) {
                for (PlayerPart part : team.players.values()) {
                    droppedSkillPending += part.droppedSkillPending;
                }
            }
            if (droppedSkillPending > 0) {
                Constants.LOG.info("Dropped {} pending skill rewards from an old save; skill rewards are paid on the spot", droppedSkillPending);
            }
            return store;
        }

        // version 1: one progress object per player; every player becomes their own solo team, nothing is dropped
        CompoundTag all = tag.getCompound("players");
        JsonObject legacy = new JsonObject();
        for (String key : all.getAllKeys()) {
            try {
                legacy.add(key, JsonParser.parseString(all.getString(key)));
            } catch (RuntimeException ex) {
                Constants.LOG.error("Could not read progress of {}: {}", key, ex.toString());
            }
        }
        store.data = TeamStoreData.fromLegacy(legacy);
        store.loadedLegacy = true;
        Constants.LOG.info("Upgraded {} per-player progress records to team records", store.data.teams.size());
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", TeamStoreData.VERSION);
        CompoundTag teams = new CompoundTag();
        data.teams.forEach((id, team) -> {
            try {
                // One compound per team, not one writeUTF string: a big book overflows the 65,535-byte string and the
                // whole save failed, taking every team's progress with it (2026-10-22 release review).
                teams.put(id.toString(), com.kuronami.questtree.progress.JsonTags.toTag(team.toJson()));
            } catch (RuntimeException ex) {
                Constants.LOG.error("Could not write progress of team {}: {}", id, ex.toString());
            }
        });
        tag.put("teams", teams);
        CompoundTag owners = new CompoundTag();
        data.owners.forEach((player, team) -> owners.putString(player.toString(), team.toString()));
        tag.put("owners", owners);
        CompoundTag migrations = new CompoundTag();
        data.migrations.forEach(migrations::putString);
        tag.put("migrations", migrations);
        if (!data.personalNodes.isEmpty()) {
            net.minecraft.nbt.ListTag personal = new net.minecraft.nbt.ListTag();
            data.personalNodes.forEach(node -> personal.add(net.minecraft.nbt.StringTag.valueOf(node)));
            tag.put("personal_nodes", personal);
        }
        return tag;
    }
}
