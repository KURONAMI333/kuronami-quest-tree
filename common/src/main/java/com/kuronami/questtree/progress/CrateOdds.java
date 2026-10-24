package com.kuronami.questtree.progress;

import java.util.List;
import java.util.Locale;

/**
 * The chance each entry of a loot crate's table shows up when one crate is opened. The roll is
 * {@link RewardRoll}'s: a weighted pick among the entries and {@code empty_weight}, repeated {@code rolls} times,
 * with weight-0 entries always granted outside the rolls. Kept without game classes so the arithmetic can be tested
 * directly.
 */
public final class CrateOdds {

    private CrateOdds() {
    }

    /**
     * @param weights the table entries' weights in order (0 = always granted, as in {@link RewardRoll})
     * @param emptyWeight the table's {@code empty_weight}
     * @param rolls how many weighted attempts one crate makes
     * @return the chance (0..1) that each entry is granted at least once
     */
    public static double[] chances(List<Double> weights, double emptyWeight, int rolls) {
        double empty = Math.max(0, emptyWeight);
        double total = empty;
        for (Double weight : weights) {
            total += Math.max(0, weight == null ? 0 : weight);
        }
        double[] out = new double[weights.size()];
        for (int i = 0; i < weights.size(); i++) {
            Double weight = weights.get(i);
            if (weight == null) {
                continue;
            }
            if (weight == 0) {
                // always granted, whether or not a roll happens
                out[i] = 1;
            } else {
                out[i] = chance(weight, total, rolls);
            }
        }
        return out;
    }

    /** The chance one pick from {@code totalWeight} lands on an entry of {@code weight}, over {@code rolls}. */
    public static double chance(double weight, double totalWeight, int rolls) {
        if (weight <= 0 || totalWeight <= 0 || rolls <= 0) {
            return 0;
        }
        return 1 - Math.pow(1 - weight / totalWeight, rolls);
    }

    /** A chance as the interface shows it: "12.5%", "&lt;0.1%" or "100%", never a sentence. */
    public static String percent(double chance) {
        if (!(chance > 0)) {
            return "0.0%";
        }
        if (chance >= 0.9995) {
            return "100%";
        }
        if (chance < 0.001) {
            return "<0.1%";
        }
        return String.format(Locale.ROOT, "%.1f%%", chance * 100);
    }
}
