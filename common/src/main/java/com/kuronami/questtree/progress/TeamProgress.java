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
    /**
     * Skill rewards the team actually paid, key {@code <node>#<rank>#<index>} (2026-10-22). One record for the team,
     * whichever member took the skill. Revocation, re-application and the respec judgement read only this set.
     */
    public final Set<String> paidSkillRewards = new HashSet<>();
    /**
     * The saved form has no {@code paid_skill_rewards} key, so this team predates the record and its taken skills are
     * filled once. A team still waiting for that migration does not write the key, because an empty one would make
     * the next load look migrated; a record it already holds is used by the migration (2026-10-22 review).
     */
    public boolean needsPaidMigration;
    private final Map<UUID, PlayerProgress> views = new HashMap<>();
    private int viewsVersion = -1;

    public TeamProgress(UUID id) {
        this.id = id;
    }

    public PlayerPart part(UUID player) {
        return players.computeIfAbsent(player, k -> new PlayerPart());
    }

    /**
     * Nodes completed by the team, or by one member plus the shared record. A leaving member's copy must not carry
     * another member's personal completion (2026-10-21 review).
     */
    public Set<String> completedFor(UUID player) {
        Set<String> completed = new HashSet<>(shared.completed);
        if (player != null) {
            PlayerPart own = players.get(player);
            if (own != null) {
                completed.addAll(own.completed);
            }
        }
        return completed;
    }

    /**
     * Moves saved values between the shared record and the members' parts after the set of personal nodes changed.
     * Keys of newly personal nodes move from shared to every part; keys that became shared merge into shared and leave
     * the parts (2026-10-21 review).
     */
    public void migratePersonal(java.util.Set<String> oldNodes, java.util.Set<String> newNodes) {
        if (players.isEmpty()) {
            // nothing of a member's own yet, so there is no personal value that a routing change could move
            return;
        }
        java.util.Set<String> toPersonal = new HashSet<>(newNodes);
        toPersonal.removeAll(oldNodes);
        java.util.Set<String> toShared = new HashSet<>(oldNodes);
        toShared.removeAll(newNodes);
        if (toPersonal.isEmpty() && toShared.isEmpty()) {
            return;
        }
        for (String key : new HashSet<>(shared.completed)) {
            if (toPersonal.contains(key)) {
                for (UUID member : new HashSet<>(players.keySet())) {
                    part(member).completed.add(key);
                }
                shared.completed.remove(key);
            }
        }
        for (UUID member : new HashSet<>(players.keySet())) {
            PlayerPart part = part(member);
            for (String key : new HashSet<>(part.completed)) {
                if (toShared.contains(key)) {
                    shared.completed.add(key);
                    part.completed.remove(key);
                }
            }
        }
        moveMapToParts(shared.completedAt, p -> p.completedAt, toPersonal);
        moveMapToParts(shared.tasks, p -> p.tasks, toPersonal);
        moveIntMapToParts(shared.repeats, p -> p.repeats, toPersonal);
        moveMapToParts(shared.cooldown, p -> p.cooldown, toPersonal);
        moveMapToShared(p -> p.completedAt, shared.completedAt, toShared);
        moveMapToShared(p -> p.tasks, shared.tasks, toShared);
        moveIntMapToShared(p -> p.repeats, shared.repeats, toShared);
        moveMapToShared(p -> p.cooldown, shared.cooldown, toShared);
        views.clear();
    }

    private static String nodeOf(String key) {
        int hash = key.lastIndexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    private void moveMapToParts(Map<String, Long> source,
                                java.util.function.Function<PlayerPart, Map<String, Long>> target,
                                Set<String> nodes) {
        for (String key : new HashSet<>(source.keySet())) {
            if (!nodes.contains(nodeOf(key))) {
                continue;
            }
            long value = source.get(key);
            for (UUID member : new HashSet<>(players.keySet())) {
                target.apply(part(member)).merge(key, value, Math::max);
            }
            source.remove(key);
        }
    }

    private void moveMapToShared(java.util.function.Function<PlayerPart, Map<String, Long>> source,
                                 Map<String, Long> target, Set<String> nodes) {
        for (UUID member : new HashSet<>(players.keySet())) {
            Map<String, Long> map = source.apply(part(member));
            for (String key : new HashSet<>(map.keySet())) {
                if (nodes.contains(nodeOf(key))) {
                    target.merge(key, map.get(key), Math::max);
                    map.remove(key);
                }
            }
        }
    }

    private void moveIntMapToParts(Map<String, Integer> source,
                                   java.util.function.Function<PlayerPart, Map<String, Integer>> target,
                                   Set<String> nodes) {
        for (String key : new HashSet<>(source.keySet())) {
            if (!nodes.contains(nodeOf(key))) {
                continue;
            }
            int value = source.get(key);
            for (UUID member : new HashSet<>(players.keySet())) {
                target.apply(part(member)).merge(key, value, Math::max);
            }
            source.remove(key);
        }
    }

    private void moveIntMapToShared(java.util.function.Function<PlayerPart, Map<String, Integer>> source,
                                    Map<String, Integer> target, Set<String> nodes) {
        for (UUID member : new HashSet<>(players.keySet())) {
            Map<String, Integer> map = source.apply(part(member));
            for (String key : new HashSet<>(map.keySet())) {
                if (nodes.contains(nodeOf(key))) {
                    target.merge(key, map.get(key), Math::max);
                    map.remove(key);
                }
            }
        }
    }

    public PlayerProgress view(UUID player) {
        if (viewsVersion != PlayerProgress.personalVersion()) {
            views.clear();
            viewsVersion = PlayerProgress.personalVersion();
        }
        return views.computeIfAbsent(player, k -> PlayerProgress.view(shared, part(k), teamClaimedRewards,
                paidSkillRewards));
    }

    /** A solo record made from a player's old per-player save. All shared values and that player's claims are kept. */
    public static TeamProgress fromLegacy(UUID player, PlayerProgress old) {
        TeamProgress team = new TeamProgress(player);
        team.shared.copySharedFrom(old);
        team.paidSkillRewards.addAll(old.paidSkillRewards);
        team.needsPaidMigration = true;
        PlayerPart part = team.part(player);
        part.claimed.addAll(old.claimed);
        part.claimedRewards.addAll(old.claimedRewards);
        part.recent.addAll(old.recent);
        part.bookGiven = old.bookGiven();
        team.absorbLegacyEmergencyCooldown();
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
        paidSkillRewards.addAll(source.paidSkillRewards);
        needsPaidMigration |= source.needsPaidMigration;
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
        solo.paidSkillRewards.addAll(paidSkillRewards);
        solo.needsPaidMigration = needsPaidMigration;
        PlayerPart own = players.get(player);
        if (own != null) {
            solo.part(player).copyFrom(own);
        }
        // a one-off skill mark belongs to the team, so a leaver keeps it: taking the same rank alone again must not
        // hand out the item or command a second time (2026-10-22 review)
        for (PlayerPart part : players.values()) {
            for (String mark : part.claimedRewards) {
                if (mark.contains("#skill")) {
                    solo.part(player).claimedRewards.add(mark);
                }
            }
        }
        return solo;
    }

    private void mergeShared(PlayerProgress other) {
        shared.completed.addAll(other.completed);
        other.completedAt.forEach((k, v) -> shared.completedAt.merge(k, v, Math::max));
        other.tasks.forEach((k, v) -> shared.tasks.merge(k, v, Math::max));
        other.taken.forEach((k, v) -> shared.taken.merge(k, v, Math::max));
        other.xp.forEach((k, v) -> shared.xp.merge(k, v, Math::max));
        other.level.forEach((k, v) -> shared.level.merge(k, v, Math::max));
        other.spent.forEach((k, v) -> shared.spent.merge(k, v, Math::max));
        other.bonus.forEach((k, v) -> shared.bonus.merge(k, v, Math::max));
        other.exchange.forEach((k, v) -> shared.exchange.merge(k, v, Math::max));
        other.repeats.forEach((k, v) -> shared.repeats.merge(k, v, Math::max));
        other.cooldown.forEach((k, v) -> shared.cooldown.merge(k, v, Math::max));
        // a chapter has one open/locked state for the whole team; when the two sides differ, closed wins so a join
        // cannot reopen a chapter someone had locked
        other.chapterOpen.forEach((chapter, open) ->
                shared.chapterOpen.merge(chapter, open, (teamOpen, joinerOpen) -> teamOpen && joinerOpen));
        shared.teamStages.addAll(other.teamStages);
    }

    /** Moves the old shared emergency wait to every member so an update does not hand out a free use. */
    private void absorbLegacyEmergencyCooldown() {
        Long legacy = shared.cooldown.remove(PlayerProgress.EMERGENCY_COOLDOWN);
        if (legacy == null) {
            return;
        }
        for (PlayerPart part : players.values()) {
            part.emergencyReadyAt = Math.max(part.emergencyReadyAt, legacy);
        }
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
        // only a migrated team writes the key: a save made while the data pack was empty or broken (no migration yet)
        // must not look migrated to the next load, or the old marks it still needs would be ignored (2026-10-22 review)
        if (!needsPaidMigration) {
            JsonArray paid = new JsonArray();
            paidSkillRewards.forEach(paid::add);
            o.add("paid_skill_rewards", paid);
        }
        return o;
    }

    public static TeamProgress fromJson(JsonObject o) {
        return fromJson(o, null);
    }

    /** As above; {@code fallbackId} is the saved map key, used when the record itself has no readable id. */
    public static TeamProgress fromJson(JsonObject o, UUID fallbackId) {
        UUID id = fallbackId;
        JsonElement savedId = o.get("id");
        if (savedId != null && savedId.isJsonPrimitive() && savedId.getAsJsonPrimitive().isString()) {
            try {
                id = UUID.fromString(savedId.getAsString());
            } catch (IllegalArgumentException ignored) {
                // a broken id is replaced by the map key when the caller has one
            }
        }
        if (id == null) {
            throw new IllegalArgumentException("Team record has no readable id");
        }
        TeamProgress team = new TeamProgress(id);
        if (o.has("shared") && o.get("shared").isJsonObject()) {
            team.shared.copySharedFrom(ProgressJson.read(o.getAsJsonObject("shared")));
        }
        if (o.has("players") && o.get("players").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("players").entrySet()) {
                try {
                    JsonObject partJson = e.getValue().getAsJsonObject();
                    team.players.put(UUID.fromString(e.getKey()), PlayerPart.fromJson(partJson));
                    // saves made while the chapter state was per member kept a chapter_open in each part: fold it
                    // into the team, with closed winning so a member's lock is not lost
                    if (partJson.has("chapter_open") && partJson.get("chapter_open").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> c : partJson.getAsJsonObject("chapter_open").entrySet()) {
                            Boolean open = PackNumbers.booleanOrNull(c.getValue());
                            if (open != null) {
                                team.shared.chapterOpen.merge(c.getKey(), open,
                                        (teamOpen, memberOpen) -> teamOpen && memberOpen);
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                    // a malformed member entry must not take the whole team down
                }
            }
        }
        if (o.has("team_claimed_rewards") && o.get("team_claimed_rewards").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("team_claimed_rewards")) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    team.teamClaimedRewards.add(e.getAsString());
                }
            }
        }
        // the key is written on every save (even empty): a save without it predates the record and is migrated once
        team.needsPaidMigration = !o.has("paid_skill_rewards");
        if (o.has("paid_skill_rewards") && o.get("paid_skill_rewards").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("paid_skill_rewards")) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    team.paidSkillRewards.add(e.getAsString());
                }
            }
        }
        team.absorbLegacyEmergencyCooldown();
        return team;
    }

    /** Parses one saved team; a bad record is reported by the caller instead of escaping as an exception. */
    public static TeamProgress parse(String json) {
        return fromJson(JsonParser.parseString(json).getAsJsonObject());
    }
}
