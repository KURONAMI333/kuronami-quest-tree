package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Gates on a task or reward (DATA_MODEL.md "条件"): every entry of {@code conditions} must hold before the task
 * counts or the reward is paid. One list is shared by tasks and rewards, so a pack author writes the same five types
 * in both places. The game-facing answers come from {@link Context}; this class stays testable without Minecraft.
 */
public final class QuestConditions {

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
        if (holder == null || !holder.has("conditions")) {
            return true;
        }
        JsonElement e = holder.get("conditions");
        if (!e.isJsonArray()) {
            return false;
        }
        for (JsonElement c : e.getAsJsonArray()) {
            if (!c.isJsonObject() || !met(c.getAsJsonObject(), context)) {
                return false;
            }
        }
        return true;
    }

    /** One condition. Unknown kinds fail closed: a typo must not open a gate. */
    public static boolean met(JsonObject c, Context context) {
        String type = str(c, "type");
        if (type.contains(":")) {
            type = type.substring(type.indexOf(':') + 1);
        }
        return switch (type) {
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
            default -> false;
        };
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static int intOf(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber()
                ? o.get(k).getAsInt() : def;
    }
}
