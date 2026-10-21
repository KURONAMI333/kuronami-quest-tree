package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;

import java.util.Set;

/**
 * Which skill rewards are paid once per rank. Respec keeps its mark so taking the same rank again cannot pay them
 * again (2026-10-21 review); pure so the rule is testable without a running game.
 */
public final class SkillRewardPolicy {

    private static final Set<String> ONE_OFF = Set.of("xp", "xp_levels", "item", "table", "advancement", "command",
            "currency", "custom");

    private SkillRewardPolicy() {
    }

    public static String mark(ProgressModel.Node n, int rank) {
        return n.key + "#skill" + rank;
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
        String type = reward.has("type") && reward.get("type").isJsonPrimitive()
                ? reward.get("type").getAsString() : "";
        return pays(type, firstTake) || (type.equals("command") && hasLockCommand(reward));
    }

    /** Whether a command reward has a {@code lock_command} to undo it (the revoke half on respec/reset). */
    public static boolean hasLockCommand(JsonObject reward) {
        JsonElement lock = reward.get("lock_command");
        return lock != null && lock.isJsonPrimitive() && lock.getAsJsonPrimitive().isString()
                && !lock.getAsString().isBlank();
    }
}
