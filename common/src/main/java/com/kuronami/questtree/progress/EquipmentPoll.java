package com.kuronami.questtree.progress;

import java.util.List;
import java.util.UUID;

/**
 * When to look at a player's worn equipment, and whether anything changed since the last look. The restriction check
 * runs every few ticks per player instead of every tick for everyone, and a player's ticks are spread by their UUID so
 * a full server does not check everyone on the same tick (2026-10-22 review, L1). Plain numbers only, so a test needs
 * no game.
 */
public final class EquipmentPoll {

    /** How many ticks apart one player's equipment check is. */
    public static final int INTERVAL = 5;

    private static final long SEED = 1125899906842597L;

    private EquipmentPoll() {
    }

    /** Whether this player's turn falls on this tick. Each UUID gets a fixed offset inside {@link #INTERVAL}. */
    public static boolean due(long tick, UUID player) {
        int offset = Math.floorMod(player.hashCode(), INTERVAL);
        return Math.floorMod(tick, INTERVAL) == offset;
    }

    /**
     * A number for the worn item ids, the loaded rules and the player's progress revision. The same equipment with the
     * same rules and progress has the same signature, so rereading the rules and their conditions is skipped; a
     * changed item, a reloaded rule set or a progress change makes a new one.
     */
    public static long signature(List<String> wornIds, long rules, long revision) {
        long h = SEED;
        h = 31 * h + rules;
        h = 31 * h + revision;
        for (String id : wornIds) {
            h = 31 * h + id.hashCode();
        }
        return h;
    }
}
