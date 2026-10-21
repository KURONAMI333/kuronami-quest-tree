package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One team's progress. A solo player is a team whose id is their UUID, so both cases use one code path.
 * <p>
 * {@link #shared} holds what every member has in common ({@code completed}, task counters, taken skills, experience,
 * levels, points, repeats, cooldowns, chapter overrides). {@link #players} holds each member's claims and recent
 * list by UUID, so one member claiming a reward cannot block the others. Rewards marked {@code "team": true} are
 * tracked in {@link #teamClaimedRewards} and settle once for the whole team.
 */
public final class TeamProgress {

    public final UUID id;
    public final PlayerProgress shared = new PlayerProgress();
    public final Map<UUID, PlayerPart> players = new LinkedHashMap<>();
    /** FTBQ's team_reward (NIL UUID): claimed once for the team. */
    public final Set<String> teamClaimedRewards = new HashSet<>();
    private final Map<UUID, PlayerProgress> views = new HashMap<>();
    private int viewsVersion = -1;

    public TeamProgress(UUID id) {
        this.id = id;
    }

    public PlayerPart part(UUID player) {
        return players.computeIfAbsent(player, k -> new PlayerPart());
    }

    public PlayerProgress view(UUID player) {
        if (viewsVersion != PlayerProgress.personalVersion()) {
            views.clear();
            viewsVersion = PlayerProgress.personalVersion();
        }
        return views.computeIfAbsent(player, k -> PlayerProgress.view(shared, part(k), teamClaimedRewards));
    }

    /** A solo record made from a player's old per-player save. All shared values and that player's claims are kept. */
    public static TeamProgress fromLegacy(UUID player, PlayerProgress old) {
        TeamProgress team = new TeamProgress(player);
        team.shared.copySharedFrom(old);
        PlayerPart part = team.part(player);
        part.claimed.addAll(old.claimed);
        part.claimedRewards.addAll(old.claimedRewards);
        part.recent.addAll(old.recent);
        part.bookGiven = old.bookGiven();
        return team;
    }

    /**
     * Folds a joining player's record into this team. Completed nodes stay completed, counters and skill values take
     * the larger value per key, and the joiner's own claims move under their UUID. Other members' claims are not
     * touched; team rewards already settled by either side stay settled.
     */
    public void mergeJoiner(UUID player, TeamProgress source) {
        mergeShared(source.shared);
        teamClaimedRewards.addAll(source.teamClaimedRewards);
        PlayerPart from = source.players.get(player);
        if (from != null) {
            part(player).copyFrom(from);
        }
        views.remove(player);
    }

    public TeamProgress leaveCopy(UUID player) {
        return leaveCopy(player, java.util.Set.of());
    }

    /**
     * The copy a member takes when they leave: the team's shared values plus that member's own claims.
     * {@code settledTeamRewards} are the team rewards of nodes the team completed; they count as claimed in the copy,
     * so leaving cannot be used to take a once-per-team reward a second time (FTBQ TeamData.mergeClaimedRewards).
     */
    public TeamProgress leaveCopy(UUID player, java.util.Set<String> settledTeamRewards) {
        TeamProgress solo = new TeamProgress(player);
        solo.shared.copySharedFrom(shared);
        solo.teamClaimedRewards.addAll(teamClaimedRewards);
        solo.teamClaimedRewards.addAll(settledTeamRewards);
        PlayerPart own = players.get(player);
        if (own != null) {
            solo.part(player).copyFrom(own);
        }
        return solo;
    }

    private void mergeShared(PlayerProgress other) {
        shared.completed.addAll(other.completed);
        other.tasks.forEach((k, v) -> shared.tasks.merge(k, v, Math::max));
        other.taken.forEach((k, v) -> shared.taken.merge(k, v, Math::max));
        other.xp.forEach((k, v) -> shared.xp.merge(k, v, Math::max));
        other.level.forEach((k, v) -> shared.level.merge(k, v, Math::max));
        other.spent.forEach((k, v) -> shared.spent.merge(k, v, Math::max));
        other.bonus.forEach((k, v) -> shared.bonus.merge(k, v, Math::max));
        other.exchange.forEach((k, v) -> shared.exchange.merge(k, v, Math::max));
        other.repeats.forEach((k, v) -> shared.repeats.merge(k, v, Math::max));
        other.cooldown.forEach((k, v) -> shared.cooldown.merge(k, v, Math::max));
        // the team's own lock override wins; one set by the joiner is used only where the team has none
        other.chapterOpen.forEach(shared.chapterOpen::putIfAbsent);
        shared.teamStages.addAll(other.teamStages);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id.toString());
        o.add("shared", ProgressJson.write(shared));
        JsonObject ps = new JsonObject();
        players.forEach((k, v) -> ps.add(k.toString(), v.toJson()));
        o.add("players", ps);
        JsonArray team = new JsonArray();
        teamClaimedRewards.forEach(team::add);
        o.add("team_claimed_rewards", team);
        return o;
    }

    public static TeamProgress fromJson(JsonObject o) {
        UUID id = UUID.fromString(o.get("id").getAsString());
        TeamProgress team = new TeamProgress(id);
        if (o.has("shared") && o.get("shared").isJsonObject()) {
            team.shared.copySharedFrom(ProgressJson.read(o.getAsJsonObject("shared")));
        }
        if (o.has("players") && o.get("players").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("players").entrySet()) {
                try {
                    team.players.put(UUID.fromString(e.getKey()), PlayerPart.fromJson(e.getValue().getAsJsonObject()));
                } catch (RuntimeException ignored) {
                    // a malformed member entry must not take the whole team down
                }
            }
        }
        if (o.has("team_claimed_rewards") && o.get("team_claimed_rewards").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("team_claimed_rewards")) {
                team.teamClaimedRewards.add(e.getAsString());
            }
        }
        return team;
    }

    /** Parses one saved team; a bad record is reported by the caller instead of escaping as an exception. */
    public static TeamProgress parse(String json) {
        return fromJson(JsonParser.parseString(json).getAsJsonObject());
    }
}
