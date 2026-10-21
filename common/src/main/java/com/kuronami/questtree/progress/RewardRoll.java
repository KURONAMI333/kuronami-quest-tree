package com.kuronami.questtree.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * FTB Quests' reward table roll ({@code RewardTable.generateWeightedRandomRewards}, 2026-10-20 review):
 * <ul>
 *   <li>an empty result is only possible when the caller includes {@code empty_weight} (loot crates do; a random
 *       reward passes {@code false}),</li>
 *   <li>entries with weight 0 are always granted, outside the rolls,</li>
 *   <li>no roll happens when every weight is 0.</li>
 * </ul>
 * Kept without game classes so the arithmetic can be tested directly.
 */
public final class RewardRoll {

    private RewardRoll() {
    }

    /**
     * @param weights the table entries' weights in order (0 = always granted)
     * @param emptyWeight the table's {@code empty_weight}
     * @param includeEmpty whether an "empty" result takes part (FTB Quests' loot crates)
     * @param rolls how many weighted attempts to make
     * @param random returns 0..1
     * @return the entry indices to grant, with weight-0 entries first
     */
    public static List<Integer> roll(List<Double> weights, double emptyWeight, boolean includeEmpty, int rolls,
                                     DoubleSupplier random) {
        double empty = includeEmpty ? Math.max(0, emptyWeight) : 0;
        double total = empty;
        for (Double w : weights) {
            total += Math.max(0, w == null ? 0 : w);
        }
        List<Integer> result = new ArrayList<>();
        if (total <= 0) {
            return result;
        }
        for (int i = 0; i < weights.size(); i++) {
            Double w = weights.get(i);
            if (w != null && w == 0) {
                result.add(i);
            }
        }
        for (int r = 0; r < Math.max(0, rolls); r++) {
            double threshold = random.getAsDouble() * total;
            double current = empty;
            if (current < threshold) {
                for (int i = 0; i < weights.size(); i++) {
                    current += Math.max(0, weights.get(i) == null ? 0 : weights.get(i));
                    if (current >= threshold) {
                        result.add(i);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /** The saved form of a roll ({@code "1,0,-1"} marks an empty pick as -1), so a retry uses the same entries. */
    public static String encode(List<Integer> picks) {
        StringBuilder b = new StringBuilder();
        for (Integer pick : picks) {
            if (!b.isEmpty()) {
                b.append(',');
            }
            b.append(pick == null ? -1 : pick);
        }
        return b.toString();
    }

    /** Reads a saved roll, or null when it is absent or unreadable (the caller rolls and records again). */
    public static List<Integer> decode(String saved) {
        if (saved == null) {
            return null;
        }
        List<Integer> picks = new ArrayList<>();
        if (saved.isEmpty()) {
            return picks;
        }
        try {
            for (String part : saved.split(",")) {
                picks.add(Integer.parseInt(part.trim()));
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return picks;
    }
}
