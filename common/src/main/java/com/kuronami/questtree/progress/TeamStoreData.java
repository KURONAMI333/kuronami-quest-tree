package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
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
        return changedTeam(player, current, t -> java.util.Set.of());
    }

    /** As above; {@code settled} names the team rewards of completed nodes, which a leaver's copy keeps as claimed. */
    public boolean changedTeam(UUID player, UUID current,
                               java.util.function.Function<TeamProgress, java.util.Set<String>> settled) {
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
                teams.put(player, source.leaveCopy(player, settled.apply(source)));
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
        return root;
    }

    public static TeamStoreData fromJson(JsonObject root) {
        TeamStoreData data = new TeamStoreData();
        JsonObject teams = root.has("teams") && root.get("teams").isJsonObject()
                ? root.getAsJsonObject("teams") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : teams.entrySet()) {
            try {
                TeamProgress t = TeamProgress.fromJson(e.getValue().getAsJsonObject());
                data.teams.put(t.id, t);
            } catch (RuntimeException ignored) {
                // one unreadable team is dropped; the rest of the world still loads
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
        return data;
    }
}
