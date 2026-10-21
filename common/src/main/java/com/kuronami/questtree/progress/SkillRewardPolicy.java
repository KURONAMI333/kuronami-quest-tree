package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which skill rewards are paid once per rank and which ones are taken back. A respec keeps the record of a one-off
 * reward, so taking the same rank again cannot pay it again; a revocable reward loses its record when it is taken
 * back, so taking the rank again pays it again (2026-10-22 review). Pure so the rules are testable without the game.
 */
public final class SkillRewardPolicy {

    private static final Set<String> ONE_OFF = Set.of("xp", "xp_levels", "item", "table", "advancement", "command",
            "currency", "custom");

    private SkillRewardPolicy() {
    }

    /**
     * The record of one reward of a rank, keyed the same way as a pending reward: {@code <node>#<rank>#<index>}.
     * The team keeps one record whichever member took the skill (2026-10-22 review).
     */
    public static String paidKey(ProgressModel.Node n, int rank, int index) {
        return n.key + "#" + rank + "#" + index;
    }

    public static String paidKey(String node, int rank, int index) {
        return node + "#" + rank + "#" + index;
    }

    /**
     * Whether this reward of the rank was never paid for the team. Only rewards whose conditions held at take time
     * were recorded, so a respec cannot undo or repay one that was never handed out (2026-10-22 review).
     */
    public static boolean owed(PlayerProgress p, ProgressModel.Node n, int rank, int index) {
        return !p.paidSkillRewards.contains(paidKey(n, rank, index));
    }

    /**
     * Records one reward once it was paid. A failed pay-out leaves no record, so the next take can pay it instead of
     * losing it for good (2026-10-22 review).
     */
    public static void recordPaid(PlayerProgress p, ProgressModel.Node n, int rank, int index, boolean paid) {
        if (paid) {
            p.paidSkillRewards.add(paidKey(n, rank, index));
        }
    }

    /**
     * Forgets the records of the revocable rewards of a node's taken ranks after they were taken back. One-off
     * rewards keep their record: they are not revoked, and taking the rank again must not pay them again.
     */
    public static void clearRevoked(PlayerProgress p, ProgressModel.Node n, int taken) {
        clearRevoked(p, n, taken, true);
    }

    /**
     * As above; {@code withPoints} is false when a chapter lock takes the effects back but leaves the points. Such a
     * lock also keeps the attribute record: unlocking puts the attribute back from that record, so forgetting it
     * would lose the bonus for good (2026-10-22 review).
     */
    public static void clearRevoked(PlayerProgress p, ProgressModel.Node n, int taken, boolean withPoints) {
        for (int rank = 0; rank < Math.max(0, taken); rank++) {
            java.util.List<JsonObject> list = ProgressRules.rewardsOfRank(n, rank);
            for (int index = 0; index < list.size(); index++) {
                JsonObject rw = list.get(index);
                String type = typeOf(rw);
                boolean undoneByLock = type.equals("tag") || (type.equals("command") && hasLockCommand(rw));
                if (revocable(rw) && (withPoints || undoneByLock)) {
                    p.paidSkillRewards.remove(paidKey(n, rank, index));
                }
            }
        }
    }

    public static boolean oneOff(String type) {
        return ONE_OFF.contains(type);
    }

    /** Whether this rank still pays the reward: the taker's first take, or a reward that is reapplied every take. */
    public static boolean pays(String type, boolean firstTake) {
        return !oneOff(type) || firstTake;
    }

    /**
     * Whether a reward object is paid on this take. A command with {@code lock_command} is not one-off: the respec runs
     * the lock half, so taking the rank again must run the unlock half again (2026-10-22 review). Commands without one
     * stay one-off, as before.
     */
    public static boolean pays(JsonObject reward, boolean firstTake) {
        return pays(typeOf(reward), firstTake) || (typeOf(reward).equals("command") && hasLockCommand(reward));
    }

    /** Whether a reward has a {@code lock_command} to undo it (the revoke half on respec/reset). */
    public static boolean hasLockCommand(JsonObject reward) {
        JsonElement lock = reward.get("lock_command");
        return lock != null && lock.isJsonPrimitive() && lock.getAsJsonPrimitive().isString()
                && !lock.getAsString().isBlank();
    }

    /**
     * Whether taking the reward back undoes something. Points, tag and attribute are re-applied by taking the rank
     * again; a command with {@code lock_command} has its lock half. The rest are one-off gifts that stay paid.
     */
    public static boolean revocable(JsonObject reward) {
        String type = typeOf(reward);
        return type.equals("points") || type.equals("tag") || type.equals("attribute")
                || (type.equals("command") && hasLockCommand(reward));
    }

    /**
     * Fills {@code team.paidSkillRewards} for a save made before the record existed, once: only a team whose saved form
     * had no {@code paid_skill_rewards} key needs it, and the flag is cleared here so a later load leaves a new save
     * alone. Old per-reward and per-rank skill marks under {@code claimedRewards} are converted first, so a reward that
     * was refused then is not recorded as paid. Every revocable reward of a taken rank is recorded even when an old
     * mark already filled the node: a converted mark can name one reward only, and a missing points record would let a
     * respec return nothing while a retake paid again (2026-10-22 review). A taken skill node with no record at all is
     * filled for every reward of its taken ranks: before the record existed the conditions were not there, so those
     * rewards had all been paid. Returns whether the saved form changed.
     */
    public static boolean migratePaidSkillRewards(TeamProgress team, ProgressModel model) {
        if (!team.needsPaidMigration) {
            return false;
        }
        team.needsPaidMigration = false;
        boolean changed = true;
        for (PlayerPart part : team.players.values()) {
            for (String mark : new java.util.ArrayList<>(part.claimedRewards)) {
                changed |= convertMark(team, model, mark);
            }
        }
        for (var e : team.shared.taken.entrySet()) {
            ProgressModel.Node n = model.nodes.get(e.getKey());
            if (n == null || !n.chapter.isSkills()) {
                continue;
            }
            boolean recorded = team.paidSkillRewards.stream().anyMatch(k -> k.startsWith(n.key + "#"));
            int taken = Math.max(0, e.getValue());
            for (int rank = 0; rank < taken; rank++) {
                java.util.List<JsonObject> list = ProgressRules.rewardsOfRank(n, rank);
                for (int index = 0; index < list.size(); index++) {
                    // a converted mark can name one reward of the node; every revocable one must be recorded as well
                    if (!recorded || revocable(list.get(index))) {
                        changed |= team.paidSkillRewards.add(paidKey(n, rank, index));
                    }
                }
            }
        }
        return changed;
    }

    /** {@code <node>#skill<rank>[#<index>]}: an old per-reward mark, or a per-rank mark covering every reward. */
    private static boolean convertMark(TeamProgress team, ProgressModel model, String mark) {
        int at = mark.lastIndexOf("#skill");
        if (at <= 0) {
            return false;
        }
        ProgressModel.Node n = model.nodes.get(mark.substring(0, at));
        if (n == null) {
            return false;
        }
        String rest = mark.substring(at + "#skill".length());
        int hash = rest.indexOf('#');
        int rank;
        int index = -1;
        try {
            rank = Integer.parseInt(hash < 0 ? rest : rest.substring(0, hash));
            if (hash >= 0) {
                index = Integer.parseInt(rest.substring(hash + 1));
            }
        } catch (NumberFormatException ex) {
            return false;
        }
        if (index >= 0) {
            return team.paidSkillRewards.add(paidKey(n, rank, index));
        }
        boolean changed = false;
        java.util.List<JsonObject> list = ProgressRules.rewardsOfRank(n, rank);
        for (int i = 0; i < list.size(); i++) {
            changed |= team.paidSkillRewards.add(paidKey(n, rank, i));
        }
        return changed;
    }

    /**
     * Whether the effect of this reward belongs to every member. The condition is judged once, for the member who
     * took the skill, and the same answer is applied to the whole team; one-off gifts and shared values stay with the
     * taker (2026-10-22 review, DATA_MODEL "スキル報酬の条件").
     */
    public static boolean teamWide(JsonObject reward) {
        String type = typeOf(reward);
        return type.equals("attribute") || type.equals("tag")
                || (type.equals("command") && hasLockCommand(reward));
    }

    /** One reward of a taken rank, with the choices already made for the taker. */
    public record Action(int index, JsonObject reward, boolean teamWide) {
    }

    /**
     * The rewards of one taken rank that are paid now. {@code conditionsHold} is called exactly once per reward, with
     * the taker's progress; {@code paid} says whether that reward already has a record (a one-off one is then skipped
     * for the whole team). Team-wide effects are kept even when they were paid before: taking the rank again puts
     * them back (2026-10-22 review).
     */
    public static List<Action> actions(List<JsonObject> rewards,
                                       java.util.function.Predicate<JsonObject> conditionsHold,
                                       java.util.function.IntPredicate paid) {
        List<Action> out = new ArrayList<>();
        for (int index = 0; index < rewards.size(); index++) {
            JsonObject reward = rewards.get(index);
            if (!conditionsHold.test(reward)) {
                continue;
            }
            boolean teamWide = teamWide(reward);
            if (!teamWide && !pays(reward, !paid.test(index))) {
                continue;
            }
            out.add(new Action(index, reward, teamWide));
        }
        return out;
    }

    private static String typeOf(JsonObject reward) {
        return reward.has("type") && reward.get("type").isJsonPrimitive()
                ? reward.get("type").getAsString() : "";
    }
}
