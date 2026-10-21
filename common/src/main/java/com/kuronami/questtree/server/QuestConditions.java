package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.Constants;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Gates on a task or reward (DATA_MODEL.md "条件"): every entry of {@code conditions} must hold before the task
 * counts or the reward is paid. One list is shared by tasks and rewards, so a pack author writes the same types in
 * both places. The five built-in types are judged here; a type they do not answer is handed to {@code addon}, which
 * is where the public registry of {@code QuestTreeApi} comes in. This class stays testable without Minecraft.
 */
public final class QuestConditions {

    /** The five built-in kinds, answered here rather than by the registry. */
    private static final Set<String> BUILT_IN = Set.of("stage", "score", "item", "advancement", "party_size");

    /** What a condition can ask the running game. */
    public interface Context {
        boolean hasStage(String stage);

        long score(String objective);

        /** Whether the player carries the item the condition names, in at least its count. */
        boolean hasItem(JsonObject condition);

        boolean advancementDone(String advancement, String criterion);

        int partySize();
    }

    private QuestConditions() {
    }

    /** All conditions on the holder; a holder without a list is open. A malformed list fails closed. */
    public static boolean allMet(JsonObject holder, Context context) {
        return allMet(holder, context, condition -> false);
    }

    /**
     * All conditions on the holder. A type the five built-ins do not answer goes to {@code addon}; when that has no
     * handler either, the entry fails closed.
     */
    public static boolean allMet(JsonObject holder, Context context, Predicate<JsonObject> addon) {
        if (holder == null || !holder.has("conditions")) {
            return true;
        }
        JsonElement e = holder.get("conditions");
        if (!e.isJsonArray()) {
            return false;
        }
        for (JsonElement c : e.getAsJsonArray()) {
            if (!c.isJsonObject() || !met(c.getAsJsonObject(), context, addon)) {
                return false;
            }
        }
        return true;
    }

    /** One condition. Unknown kinds fail closed: a typo must not open a gate. */
    public static boolean met(JsonObject c, Context context) {
        return met(c, context, condition -> false);
    }

    /** One condition; a kind the built-ins do not know is asked of {@code addon}. */
    public static boolean met(JsonObject c, Context context, Predicate<JsonObject> addon) {
        String type = builtInName(str(c, "type"));
        return switch (type == null ? "" : type) {
            case "stage" -> {
                String stage = str(c, "stage");
                yield !stage.isEmpty() && context.hasStage(stage);
            }
            case "score" -> {
                String objective = str(c, "objective");
                if (objective.isEmpty()) {
                    objective = str(c, "scoreboard");
                }
                yield !objective.isEmpty() && context.score(objective) >= Math.max(1, intOf(c, "min", 1));
            }
            case "item" -> context.hasItem(c);
            case "advancement" -> {
                String advancement = str(c, "advancement");
                yield !advancement.isEmpty() && context.advancementDone(advancement, str(c, "criterion"));
            }
            case "party_size" -> context.partySize() >= Math.max(1, intOf(c, "min", 1));
            default -> addon.test(c);
        };
    }

    /**
     * The built-in kind a condition's type names, or null when it names a kind the registry owns. Only an
     * unqualified name or this mod's own namespace is built in: another mod's {@code example:item} must always reach
     * the registry, even though the id after the colon is one of ours (2026-10-22 review).
     */
    static String builtInName(String type) {
        int colon = type.indexOf(':');
        if (colon < 0) {
            return type;
        }
        return Constants.MOD_ID.equals(type.substring(0, colon)) ? type.substring(colon + 1) : null;
    }

    /** Whether a condition's raw type is one of the five built-in kinds. */
    static boolean isBuiltIn(String type) {
        String name = builtInName(type);
        return name != null && BUILT_IN.contains(name);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static int intOf(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber()
                ? o.get(k).getAsInt() : def;
    }
}
