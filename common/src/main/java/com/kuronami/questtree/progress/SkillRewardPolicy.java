package com.kuronami.questtree.progress;

import com.kuronami.questtree.data.ProgressModel;

import java.util.Set;

/**
 * Which skill rewards are paid once per rank. Respec keeps its mark so taking the same rank again cannot pay them
 * again (2026-10-21 review); pure so the rule is testable without a running game.
 */
public final class SkillRewardPolicy {

    private static final Set<String> ONE_OFF = Set.of("xp", "xp_levels", "item", "table", "advancement", "command");

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
}
