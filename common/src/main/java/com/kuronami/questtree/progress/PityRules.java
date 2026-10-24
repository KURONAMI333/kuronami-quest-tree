package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * A loot crate's ceiling: after {@code after} rolls that did not take the chosen entry, the next roll takes it and
 * the count starts again. Per player and per table, so one player's unlucky streak never moves another's.
 * <p>
 * The table writes it as {@code "pity": {"after": 20, "entry": 2}}. {@code entry} may be left out: the entry with the
 * smallest weight above 0 (the first one on a tie) is the one the ceiling takes. Kept without game classes so the
 * arithmetic can be tested directly.
 */
public final class PityRules {

    private PityRules() {
    }

    /** {@code after} rolls without the entry, then {@code entry} is taken. */
    public record Settings(int after, int entry) {
    }

    /**
     * @param picks the entry indices to grant, weight-0 entries first (the same order {@link RewardRoll} uses)
     * @param misses how many rolls since the entry was last taken; 0 means the ceiling starts over
     */
    public record Result(List<Integer> picks, int misses) {
    }

    /**
     * Reads a table's ceiling, or null when the table has none or its shape cannot be used (no ceiling then).
     *
     * @param weights the table entries' weights in order, as {@link RewardRoll} reads them
     */
    public static Settings settings(JsonObject table, List<Double> weights) {
        if (table == null || !table.has("pity") || !table.get("pity").isJsonObject()) {
            return null;
        }
        JsonObject pity = table.getAsJsonObject("pity");
        JsonElement after = pity.get("after");
        if (after == null || !after.isJsonPrimitive() || !after.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        int rolls = after.getAsInt();
        if (rolls < 1) {
            return null;
        }
        int entry = -1;
        JsonElement e = pity.get("entry");
        if (e == null) {
            entry = rarest(weights);
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            entry = e.getAsInt();
        }
        if (entry < 0 || entry >= weights.size()) {
            return null;
        }
        Double weight = weights.get(entry);
        if (weight == null || weight <= 0) {
            // an always-granted entry is already taken every roll, so a ceiling on it would pay it twice
            return null;
        }
        return new Settings(rolls, entry);
    }

    /** The entry a table without {@code pity.entry} means: the smallest weight above 0, first one on a tie. */
    public static int rarest(List<Double> weights) {
        int best = -1;
        double bestWeight = Double.MAX_VALUE;
        for (int i = 0; i < weights.size(); i++) {
            Double weight = weights.get(i);
            if (weight == null || weight <= 0 || weight >= bestWeight) {
                continue;
            }
            best = i;
            bestWeight = weight;
        }
        return best;
    }

    /**
     * Rolls like {@link RewardRoll}, counting the rolls that did not take the ceiling's entry. An empty pick counts
     * too: a crate that gives nothing still brings the ceiling closer. A roll at the ceiling takes the entry without
     * rolling, and any roll that takes it starts the count again.
     *
     * @param misses the count saved for this player and table
     */
    public static Result roll(List<Double> weights, double emptyWeight, boolean includeEmpty, int rolls, int misses,
                              Settings settings, DoubleSupplier random) {
        List<Integer> picks = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) {
            Double weight = weights.get(i);
            if (weight != null && weight == 0) {
                picks.add(i);
            }
        }
        int count = Math.max(0, misses);
        double empty = includeEmpty ? Math.max(0, emptyWeight) : 0;
        double total = empty;
        for (Double weight : weights) {
            total += Math.max(0, weight == null ? 0 : weight);
        }
        if (total <= 0) {
            return new Result(picks, count);
        }
        for (int r = 0; r < Math.max(0, rolls); r++) {
            if (count + 1 >= settings.after()) {
                picks.add(settings.entry());
                count = 0;
                continue;
            }
            int pick = pick(weights, empty, total, random.getAsDouble());
            if (pick >= 0) {
                picks.add(pick);
            }
            count = pick == settings.entry() ? 0 : count + 1;
        }
        return new Result(picks, count);
    }

    /** One weighted pick, or -1 for an empty result (the same arithmetic as {@link RewardRoll}). */
    private static int pick(List<Double> weights, double empty, double total, double random) {
        double threshold = random * total;
        double current = empty;
        if (current >= threshold) {
            return -1;
        }
        for (int i = 0; i < weights.size(); i++) {
            current += Math.max(0, weights.get(i) == null ? 0 : weights.get(i));
            if (current >= threshold) {
                return i;
            }
        }
        return -1;
    }
}
