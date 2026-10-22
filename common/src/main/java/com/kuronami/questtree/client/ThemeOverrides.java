package com.kuronami.questtree.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-chapter and per-quest colour overrides of the palette (2026-10-22, FTB Quests' theme selectors): a pack can
 * recolour one chapter's tiles, or one quest's, without restyling the whole screen. The file is
 * {@code assets/kuronami_quest_tree/gui/theme.json}; see {@code _docs/RESOURCE_PACK.md}.
 * <p>
 * Resolution is default palette, then the chapter, then the quest: the quest's entry wins over its chapter's, and a
 * colour nobody overrides keeps the palette's value. Keys are the same names as {@code palette.json}, so a pack author
 * learns one set of names. This class is plain Java (no game classes) so the order can be tested without a client.
 */
public final class ThemeOverrides {

    public static final ThemeOverrides EMPTY = new ThemeOverrides(Map.of(), Map.of());

    /** Chapter id ({@code namespace:path}) to palette key to colour. */
    private final Map<String, Map<String, Integer>> chapters;
    /** Node key ({@code namespace:path/node}) to palette key to colour. */
    private final Map<String, Map<String, Integer>> quests;

    private ThemeOverrides(Map<String, Map<String, Integer>> chapters, Map<String, Map<String, Integer>> quests) {
        this.chapters = chapters;
        this.quests = quests;
    }

    /**
     * Reads the {@code chapters} and {@code quests} objects. A bad colour, or an entry that is not an object, is
     * skipped rather than failing the whole file, so one typo does not drop every other override.
     */
    public static ThemeOverrides parse(JsonObject root) {
        if (root == null) {
            return EMPTY;
        }
        Map<String, Map<String, Integer>> chapters = read(root.get("chapters"));
        Map<String, Map<String, Integer>> quests = read(root.get("quests"));
        if (chapters.isEmpty() && quests.isEmpty()) {
            return EMPTY;
        }
        return new ThemeOverrides(chapters, quests);
    }

    private static Map<String, Map<String, Integer>> read(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            Map<String, Integer> colors = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> color : entry.getValue().getAsJsonObject().entrySet()) {
                Integer parsed = parseColor(color.getValue());
                if (parsed != null) {
                    colors.put(color.getKey(), parsed);
                }
            }
            if (!colors.isEmpty()) {
                out.put(entry.getKey(), colors);
            }
        }
        return out;
    }

    /** {@code "#RRGGBB"} or {@code "#AARRGGBB"}; null when the value is not a colour. */
    static Integer parseColor(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            String text = element.getAsString().trim();
            String hex = text.startsWith("#") ? text.substring(1) : text;
            long value = Long.parseLong(hex, 16);
            return (int) (hex.length() <= 6 ? 0xFF000000L | value : value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** True when no chapter or quest overrides anything, so the screen can skip the extra lookups. */
    public boolean isEmpty() {
        return chapters.isEmpty() && quests.isEmpty();
    }

    /**
     * The colour for {@code paletteKey} on the node {@code nodeKey}: the default palette value, overridden by the
     * node's chapter, overridden by the node itself. A null or malformed node key only applies the default.
     */
    public int resolve(String paletteKey, int fallback, String nodeKey) {
        if (isEmpty() || paletteKey == null) {
            return fallback;
        }
        int value = fallback;
        if (nodeKey != null && !nodeKey.isEmpty()) {
            int slash = nodeKey.lastIndexOf('/');
            if (slash > 0) {
                Integer chapter = lookup(chapters, nodeKey.substring(0, slash), paletteKey);
                if (chapter != null) {
                    value = chapter;
                }
            }
            Integer quest = lookup(quests, nodeKey, paletteKey);
            if (quest != null) {
                value = quest;
            }
        }
        return value;
    }

    private static Integer lookup(Map<String, Map<String, Integer>> map, String id, String key) {
        Map<String, Integer> colors = map.get(id);
        return colors == null ? null : colors.get(key);
    }
}
