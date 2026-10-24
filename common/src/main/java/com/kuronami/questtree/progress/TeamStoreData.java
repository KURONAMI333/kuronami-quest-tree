package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The whole saved progress file as plain data: one record per team id, plus the last team each player was resolved
 * to. Kept free of Minecraft classes so the upgrade and the join/leave rules are unit-testable.
 * <p>
 * Version 1 files held one record per player under {@code players}. {@link #fromLegacy(JsonObject)} turns each of
 * those into a solo team (id = player UUID) without losing shared values, claims, recent events or the book flag.
 */
public final class TeamStoreData {

    public static final int VERSION = 2;

    public final Map<UUID, TeamProgress> teams = new LinkedHashMap<>();
    /** The team each player's progress was last keyed by; a change is what triggers a join merge or a leave copy. */
    public final Map<UUID, UUID> owners = new LinkedHashMap<>();
    /**
     * Progress migrations already applied, source key to {@code <hash of the input files>@<time>} (2026-10-20). A
     * second apply of the same source after people have played could bring back repeatable state that was cleared and
     * skills that were erased, so the command refuses it unless forced.
     */
    public final Map<String, String> migrations = new LinkedHashMap<>();
    /** The set of personal node keys the saved values were routed with; a change is migrated on the next view. */
    public final Set<String> personalNodes = new HashSet<>();

    public TeamProgress team(UUID id) {
        return teams.computeIfAbsent(id, TeamProgress::new);
    }

    public UUID teamOf(UUID player) {
        return owners.getOrDefault(player, player);
    }

    /**
     * Points a player at the team they are in now. Returns true when that changed, after folding their old record in
     * (join) or copying the team out to a new solo record (leave). A player who was never seen starts solo.
     */
    public boolean changedTeam(UUID player, UUID current) {
        return changedTeam(player, current, (t, p) -> java.util.Set.of());
    }

    /** As above; {@code settled} names the team rewards of completed nodes, which a leaver's copy keeps as claimed. */
    public boolean changedTeam(UUID player, UUID current,
                               java.util.function.BiFunction<TeamProgress, UUID, java.util.Set<String>> settled) {
        UUID old = owners.get(player);
        owners.put(player, current);
        if (old == null) {
            // never seen by KQT: the player's own record is what they bring into a team (an FTB Teams member who
            // has not logged in since this version, for example), so treat it as a solo record
            if (current.equals(player)) {
                return false;
            }
            old = player;
        }
        if (old.equals(current)) {
            return false;
        }
        if (current.equals(player)) {
            // left (or was kicked from) a team: take a copy of the team's progress as a new solo record
            TeamProgress source = teams.get(old);
            if (source != null) {
                teams.put(player, source.leaveCopy(player, settled.apply(source, player)));
            }
        } else {
            // joined (or moved to) a team: fold the old record into it
            TeamProgress target = team(current);
            TeamProgress source = teams.get(old);
            if (source != null) {
                target.mergeJoiner(player, source);
            }
            if (old.equals(player)) {
                teams.remove(old);
            }
        }
        return true;
    }

    /**
     * Fills the paid-skill record of saves made before it existed, for the loaded data. Idempotent: a node that
     * already has a record is left alone. Returns whether anything changed.
     */
    public boolean migratePaidSkillRewards(com.kuronami.questtree.data.ProgressModel model) {
        if (model == null || model.nodes.isEmpty()) {
            // an unreadable or empty pack is not proof that no skill was taken
            return false;
        }
        boolean changed = false;
        for (TeamProgress team : teams.values()) {
            changed |= SkillRewardPolicy.migratePaidSkillRewards(team, model);
        }
        return changed;
    }

    /** Version 1: {@code players} maps a UUID string to that player's saved progress object. */
    public static TeamStoreData fromLegacy(JsonObject players) {
        TeamStoreData data = new TeamStoreData();
        for (Map.Entry<String, JsonElement> e : players.entrySet()) {
            try {
                UUID player = UUID.fromString(e.getKey());
                PlayerProgress old = e.getValue().isJsonObject()
                        ? ProgressJson.read(e.getValue().getAsJsonObject())
                        : ProgressJson.read(JsonParser.parseString(e.getValue().getAsString()).getAsJsonObject());
                data.teams.put(player, TeamProgress.fromLegacy(player, old));
                data.owners.put(player, player);
            } catch (RuntimeException ignored) {
                // a broken player entry is skipped by the loader, which already logs it
            }
        }
        return data;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject teamJson = new JsonObject();
        teams.forEach((id, t) -> teamJson.add(id.toString(), t.toJson()));
        root.add("teams", teamJson);
        JsonObject ownerJson = new JsonObject();
        owners.forEach((player, team) -> ownerJson.addProperty(player.toString(), team.toString()));
        root.add("owners", ownerJson);
        JsonObject migrationJson = new JsonObject();
        migrations.forEach(migrationJson::addProperty);
        root.add("migrations", migrationJson);
        com.google.gson.JsonArray personal = new com.google.gson.JsonArray();
        personalNodes.forEach(personal::add);
        root.add("personal_nodes", personal);
        return root;
    }

    public static TeamStoreData fromJson(JsonObject root) {
        TeamStoreData data = new TeamStoreData();
        JsonObject teams = root.has("teams") && root.get("teams").isJsonObject()
                ? root.getAsJsonObject("teams") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : teams.entrySet()) {
            try {
                UUID key = null;
                try {
                    key = UUID.fromString(e.getKey());
                } catch (IllegalArgumentException ignored) {
                    // the record's own id still decides when the map key is not a UUID
                }
                TeamProgress t = TeamProgress.fromJson(e.getValue().getAsJsonObject(), key);
                data.teams.put(t.id, t);
            } catch (RuntimeException ex) {
                // one unreadable team is dropped and the rest of the world still loads, but the next save will not
                // write it again, so say which one went (2026-10-23 review)
                Constants.LOG.error("Could not read team {}: {}", e.getKey(), ex.toString());
            }
        }
        JsonObject owners = root.has("owners") && root.get("owners").isJsonObject()
                ? root.getAsJsonObject("owners") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : owners.entrySet()) {
            try {
                data.owners.put(UUID.fromString(e.getKey()), UUID.fromString(e.getValue().getAsString()));
            } catch (RuntimeException ignored) {
                // an invalid owner entry falls back to "solo"
            }
        }
        if (root.has("migrations") && root.get("migrations").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("migrations").entrySet()) {
                JsonElement value = e.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    data.migrations.put(e.getKey(), value.getAsString());
                }
            }
        }
        if (root.has("personal_nodes") && root.get("personal_nodes").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("personal_nodes")) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    data.personalNodes.add(e.getAsString());
                }
            }
        }
        return data;
    }

    /**
     * Routes saved values after the set of personal nodes changed, and remembers the new set.
     *
     * @param modelEmpty the loaded model has no nodes at all. An unreadable or empty pack must not turn saved personal
     *                   progress into shared progress (2026-10-22 review). An empty {@code current} with a loaded
     *                   model is a real change: the pack made every node shared, so the values do migrate.
     */
    public boolean migratePersonalNodes(Set<String> current, boolean modelEmpty) {
        if (modelEmpty) {
            return false;
        }
        if (personalNodes.equals(current)) {
            return false;
        }
        Set<String> old = new HashSet<>(personalNodes);
        for (TeamProgress team : teams.values()) {
            team.migratePersonal(old, current);
        }
        personalNodes.clear();
        personalNodes.addAll(current);
        return true;
    }
}
