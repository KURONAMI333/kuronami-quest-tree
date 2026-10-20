package com.kuronami.questtree.migration;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.progress.PlayerPart;
import com.kuronami.questtree.progress.TeamProgress;
import com.kuronami.questtree.progress.TeamStoreData;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Folds FTB Quests team progress into KQT team records (2026-10-20). Merge-only: nothing KQT already has is removed or
 * lowered; sets are united and counters take the larger value, so running it twice, or after players have played on,
 * changes nothing the second time.
 * <ul>
 *   <li>Shared values (completed quests, task counters, repeat counts and cooldowns) go to the KQT team of every member
 *       of the FTBQ team. Which KQT team a player belongs to now is asked through {@code teamOf}: with FTB Teams that is
 *       the FTBQ team itself, with KQT's own parties it can be a different one or the player alone.</li>
 *   <li>Reward claims are per player and go to that player's part of their KQT team. A claim under the NIL uuid is a
 *       team reward ({@code team_reward}); it is settled once for the whole KQT team.</li>
 *   <li>A node counts as {@code claimed} for a player only when every reward index of it is claimed by them or by the
 *       team, which is KQT's own rule.</li>
 *   <li>{@code started} has no KQT counterpart and is only counted.</li>
 * </ul>
 * Nothing is guessed: an id that is not in the book, a node the pack does not have, or a task or reward whose position
 * differs between book and pack is listed in the report and skipped.
 */
public final class FtbqMigration {

    /** Everything a run reads; {@code membership} may be null when FTB Teams files are absent. */
    public record Input(String namespace, ProgressModel model, FtbqBook book, List<FtbqTeamData> teams,
                        FtbTeamsMembers membership, Function<UUID, UUID> teamOf) {
    }

    private final Input in;
    private final TeamStoreData store;
    private final MigrationReport report;
    private final Set<UUID> players = new HashSet<>();
    /** (KQT team, player, node key) whose {@code claimed} flag needs another look after the claims are in. */
    private final Set<String> touched = new LinkedHashSet<>();

    private FtbqMigration(Input in, TeamStoreData store, MigrationReport report) {
        this.in = in;
        this.store = store;
        this.report = report;
    }

    /** Merges into {@code store} (pass a copy for a dry run) and fills {@code report}. */
    public static void run(Input in, TeamStoreData store, MigrationReport report) {
        new FtbqMigration(in, store, report).run();
    }

    private void run() {
        for (FtbqTeamData team : in.teams()) {
            report.add("teams", 1);
            for (String bad : team.badKeys) {
                report.unresolved("unreadable_key", team.id + " " + bad);
            }
            migrateTeam(team);
        }
        report.add("players", players.size());
        for (String t : touched) {
            String[] p = t.split("\\|", 3);
            settleClaimed(UUID.fromString(p[0]), UUID.fromString(p[1]), p[2]);
        }
    }

    private void migrateTeam(FtbqTeamData team) {
        Set<UUID> members = in.membership() == null ? null : in.membership().members(team.id);
        if (members == null) {
            // no FTB Teams file: the FTBQ team is a player on their own (a player team's id is the player's uuid)
            members = Set.of(team.id);
        }
        players.addAll(members);
        Set<UUID> targets = new LinkedHashSet<>();
        for (UUID m : members) {
            targets.add(in.teamOf().apply(m));
        }
        String who = team.id.toString();

        for (Map.Entry<Long, Long> e : team.completed.entrySet()) {
            long id = e.getKey();
            FtbqBook.QuestRef q = in.book().quest(id);
            if (q != null) {
                String key = nodeKey(q, who, id);
                if (key != null) {
                    report.add("quests_found", 1);
                    boolean added = false;
                    for (UUID t : targets) {
                        added |= store.team(t).shared.completed.add(key);
                    }
                    report.add("quests_added", added ? 1 : 0);
                }
            } else if (in.book().task(id) != null || in.book().isChapter(id)) {
                // FTB Quests writes completed tasks and chapters into the same map; they carry no extra information
                report.add("completed_tasks_and_chapters_skipped", 1);
            } else {
                report.unresolved("completed", FtbqIds.hex(id) + " team " + who);
            }
        }

        for (Map.Entry<Long, Long> e : team.taskProgress.entrySet()) {
            long id = e.getKey();
            String key = taskKey(id, who, "task_progress");
            if (key == null) {
                continue;
            }
            report.add("tasks_found", 1);
            boolean added = false;
            for (UUID t : targets) {
                Long before = store.team(t).shared.tasks.get(key);
                if (before == null || before < e.getValue()) {
                    store.team(t).shared.tasks.put(key, e.getValue());
                    added = true;
                }
            }
            report.add("tasks_added", added ? 1 : 0);
        }

        for (Map.Entry<Long, Integer> e : team.completionCount.entrySet()) {
            String key = questKeyOf(e.getKey(), who, "completion_count");
            if (key == null) {
                continue;
            }
            boolean added = false;
            for (UUID t : targets) {
                Integer before = store.team(t).shared.repeats.get(key);
                if (before == null || before < e.getValue()) {
                    store.team(t).shared.repeats.put(key, e.getValue());
                    added = true;
                }
            }
            report.add("repeats_added", added ? 1 : 0);
        }
        for (Map.Entry<Long, Long> e : team.repeatable.entrySet()) {
            String key = questKeyOf(e.getKey(), who, "repeatable");
            if (key == null) {
                continue;
            }
            boolean added = false;
            for (UUID t : targets) {
                Long before = store.team(t).shared.cooldown.get(key);
                if (before == null || before < e.getValue()) {
                    store.team(t).shared.cooldown.put(key, e.getValue());
                    added = true;
                }
            }
            report.add("cooldowns_added", added ? 1 : 0);
        }
        report.add("started_not_carried", team.started.size());

        for (FtbqTeamData.Claim c : team.claims) {
            claim(c, targets, members, who);
        }
    }

    private void claim(FtbqTeamData.Claim c, Set<UUID> targets, Set<UUID> members, String who) {
        FtbqBook.PartRef r = in.book().reward(c.reward());
        if (r == null) {
            report.unresolved("reward", FtbqIds.hex(c.reward()) + " team " + who);
            return;
        }
        if (!r.converted()) {
            report.add("rewards_not_converted", 1);
            return;
        }
        String node = nodeKey(r.quest(), who, c.reward());
        if (node == null) {
            return;
        }
        ProgressModel.Node n = in.model().nodes.get(node);
        if (r.index() >= n.rewards.size()) {
            report.unresolved("reward_index", FtbqIds.hex(c.reward()) + " " + node + "#" + r.index()
                    + " (pack has " + n.rewards.size() + " rewards)");
            return;
        }
        String key = node + "#" + r.index();
        if (c.player().equals(FtbqIds.NIL)) {
            report.add("team_rewards_found", 1);
            boolean added = false;
            for (UUID t : targets) {
                added |= store.team(t).teamClaimedRewards.add(key);
                for (UUID m : members) {
                    if (in.teamOf().apply(m).equals(t)) {
                        touched.add(t + "|" + m + "|" + node);
                    }
                }
            }
            report.add("team_rewards_added", added ? 1 : 0);
        } else {
            players.add(c.player());
            UUID t = in.teamOf().apply(c.player());
            report.add("rewards_found", 1);
            boolean added = store.team(t).part(c.player()).claimedRewards.add(key);
            report.add("rewards_added", added ? 1 : 0);
            touched.add(t + "|" + c.player() + "|" + node);
        }
    }

    /** KQT marks a node claimed when every reward is; the flag is only derived, never taken from one reward. */
    private void settleClaimed(UUID teamId, UUID player, String node) {
        ProgressModel.Node n = in.model().nodes.get(node);
        if (n == null || n.rewards.isEmpty()) {
            return;
        }
        TeamProgress team = store.team(teamId);
        PlayerPart part = team.part(player);
        for (int i = 0; i < n.rewards.size(); i++) {
            String key = node + "#" + i;
            if (!part.claimedRewards.contains(key) && !team.teamClaimedRewards.contains(key)) {
                return;
            }
        }
        if (part.claimed.add(node)) {
            report.add("nodes_claimed_added", 1);
        }
    }

    // ------------------------------------------------------------------ id resolution

    /** The node key of a quest that exists in the pack, or null (and an entry in the report). */
    private String nodeKey(FtbqBook.QuestRef q, String who, long id) {
        String key = q.key(in.namespace());
        if (!in.model().nodes.containsKey(key)) {
            report.unresolved("node_missing", key + " (" + FtbqIds.hex(id) + ")");
            return null;
        }
        return key;
    }

    private String questKeyOf(long id, String who, String field) {
        FtbqBook.QuestRef q = in.book().quest(id);
        if (q == null) {
            report.unresolved(field, FtbqIds.hex(id) + " team " + who);
            return null;
        }
        return nodeKey(q, who, id);
    }

    /** {@code <node key>#<task index>} for a task id, checked against the pack; null when it cannot be mapped. */
    private String taskKey(long id, String who, String field) {
        FtbqBook.PartRef t = in.book().task(id);
        if (t == null) {
            report.unresolved(field, FtbqIds.hex(id) + " team " + who);
            return null;
        }
        if (!t.converted()) {
            report.add("tasks_not_converted", 1);
            return null;
        }
        String node = nodeKey(t.quest(), who, id);
        if (node == null) {
            return null;
        }
        ProgressModel.Node n = in.model().nodes.get(node);
        if (t.index() >= n.tasks.size()) {
            report.unresolved("task_index", FtbqIds.hex(id) + " " + node + "#" + t.index()
                    + " (pack has " + n.tasks.size() + " tasks)");
            return null;
        }
        // the import wrote the task id at the end of the task's text key; a different id means the pack was edited
        JsonObject task = n.tasks.get(t.index());
        if (task.has("text") && task.get("text").isJsonPrimitive()) {
            String text = task.get("text").getAsString();
            if (!text.endsWith(".task." + t.rawId())) {
                report.unresolved("task_moved", FtbqIds.hex(id) + " " + node + "#" + t.index());
                return null;
            }
        }
        return node + "#" + t.index();
    }
}
