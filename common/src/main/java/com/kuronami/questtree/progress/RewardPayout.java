package com.kuronami.questtree.progress;

import java.util.List;

/**
 * Hands out the entries of a reward table so a failed pay-out can be retried without giving a succeeded entry twice
 * (2026-10-22 review). Each entry is marked in the player's reward rolls once it is paid; the marks and the recorded
 * roll are dropped together when the whole table is through.
 */
public final class RewardPayout {

    /** Pays one table entry; {@code index} is the table entry, {@code position} the place in this pay-out. */
    public interface EntryPay {
        boolean pay(int index, int position);
    }

    private RewardPayout() {
    }

    /** Key of one paid entry of the table at {@code key}. */
    public static String paidKey(String key, int position) {
        return key + ">paid>" + position;
    }

    /**
     * Pays the entries in order, skipping the ones already marked. Returns true when every entry is through; the
     * marks are then removed.
     */
    public static boolean pay(PlayerProgress p, String key, List<Integer> entries, EntryPay pay) {
        boolean all = true;
        for (int position = 0; position < entries.size(); position++) {
            if (p.rewardRolls.containsKey(paidKey(key, position))) {
                continue;
            }
            if (pay.pay(entries.get(position), position)) {
                p.rewardRolls.put(paidKey(key, position), "1");
            } else {
                all = false;
            }
        }
        if (all) {
            clear(p, key, entries.size());
        }
        return all;
    }

    /** Drops the paid marks of a table whose pay-out is through. */
    public static void clear(PlayerProgress p, String key, int count) {
        for (int position = 0; position < count; position++) {
            p.rewardRolls.remove(paidKey(key, position));
        }
    }
}
