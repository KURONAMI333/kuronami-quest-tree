package com.kuronami.questtree.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.function.Predicate;

/**
 * A {@code build} task's completion rule: every block of the written offset layout sits at that offset from the block
 * the player placed. Kept free of Minecraft types so the rule can be checked without a running game.
 * <p>
 * Block state properties are not looked at (a torch may face any way, a stair may be any shape); extra blocks in the
 * area do not matter. The anchor is the placed block's own position, so the layout is written relative to it.
 */
public final class BuildTaskRules {

    /** The most block entries a task may write. */
    public static final int MAX_BLOCKS = 256;

    /** The largest offset from the anchor on any axis, in blocks. */
    public static final int MAX_OFFSET = 32;

    private BuildTaskRules() {
    }

    /** The blocks around the anchor, as the running game answers them. */
    public interface BlockAt {

        /** The full id of the block at this offset, such as {@code minecraft:stone}. */
        String id(int dx, int dy, int dz);

        /** Whether the block at this offset is in the tag, named without its leading {@code #}. */
        boolean tag(int dx, int dy, int dz, String tag);
    }

    /**
     * Whether every written block sits where the layout says. {@code blocks} is required and must be a non-empty array;
     * a missing or broken entry means the task cannot be complete.
     */
    public static boolean matches(JsonObject task, BlockAt at) {
        if (task == null || at == null) {
            return false;
        }
        JsonElement blocks = task.get("blocks");
        if (blocks == null || !blocks.isJsonArray() || blocks.getAsJsonArray().isEmpty()
                || blocks.getAsJsonArray().size() > MAX_BLOCKS) {
            return false;
        }
        for (JsonElement element : blocks.getAsJsonArray()) {
            Entry entry = entry(element);
            if (entry == null) {
                return false;
            }
            int dx = entry.x();
            int dy = entry.y();
            int dz = entry.z();
            // the same limits the validator reports: a layout past them never completes, so one placement cannot
            // make the server read an unbounded area
            if (Math.abs(dx) > MAX_OFFSET || Math.abs(dy) > MAX_OFFSET || Math.abs(dz) > MAX_OFFSET) {
                return false;
            }
            if (!BlockTaskRules.matches(entry.spec(), at.id(dx, dy, dz), tag -> at.tag(dx, dy, dz, tag))) {
                return false;
            }
        }
        return true;
    }

    /** How many block entries can be read; a broken entry is not counted. */
    public static int size(JsonObject task) {
        if (task == null) {
            return 0;
        }
        JsonElement blocks = task.get("blocks");
        if (blocks == null || !blocks.isJsonArray()) {
            return 0;
        }
        int size = 0;
        for (JsonElement element : blocks.getAsJsonArray()) {
            if (entry(element) != null) {
                size++;
            }
        }
        return size;
    }

    /**
     * Whether the placed block is the anchor the task waits for. With no {@code anchor} any placement triggers the
     * check. {@code placedBlockId} is the placed block's full id and {@code hasTag} answers tag names without the
     * leading {@code #}.
     */
    public static boolean matchesAnchor(JsonObject task, String placedBlockId, Predicate<String> hasTag) {
        if (task == null) {
            return false;
        }
        JsonElement anchor = task.get("anchor");
        if (anchor == null) {
            return true;
        }
        if (!anchor.isJsonPrimitive()) {
            return false;
        }
        return BlockTaskRules.matches(anchor.getAsString(), placedBlockId, hasTag);
    }

    /** One readable {@code blocks[]} entry, or null when the entry cannot be read. */
    private static Entry entry(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject entry = element.getAsJsonObject();
        int[] pos = pos(entry.get("pos"));
        if (pos == null) {
            return null;
        }
        JsonElement block = entry.get("block");
        if (block == null || !block.isJsonPrimitive() || !block.getAsJsonPrimitive().isString()) {
            return null;
        }
        String spec = block.getAsString();
        if (spec.isEmpty()) {
            return null;
        }
        return new Entry(pos[0], pos[1], pos[2], spec);
    }

    /** Three whole offsets, or null when the value is not that. */
    private static int[] pos(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            return null;
        }
        int[] pos = new int[3];
        for (int i = 0; i < 3; i++) {
            Integer value = whole(array.get(i));
            if (value == null) {
                return null;
            }
            pos[i] = value;
        }
        return pos;
    }

    /** A JSON number or numeric string without a fractional part, or null. */
    private static Integer whole(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            if (element.getAsJsonPrimitive().isNumber()) {
                double value = element.getAsDouble();
                if (!Double.isFinite(value) || value != Math.rint(value)) {
                    return null;
                }
                return (int) value;
            }
            if (element.getAsJsonPrimitive().isString()) {
                return Integer.parseInt(element.getAsString().trim());
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private record Entry(int x, int y, int z, String spec) {
    }
}
