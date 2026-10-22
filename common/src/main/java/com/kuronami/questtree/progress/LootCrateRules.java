package com.kuronami.questtree.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * Loot crate data, read the way FTB Quests reads it (2026-10-22, p1-crates): a reward table carries
 * {@code loot_crate} with a string id, an optional item name, a colour, a glow flag and drop weights for
 * passive/monster/boss. Kept free of game classes so the weights and defaults can be tested directly.
 */
public final class LootCrateRules {

    private static final int WHITE = 0xFFFFFF;

    private LootCrateRules() {
    }

    /** One table's crate, with every missing field filled from the FTB Quests defaults for its string id. */
    public record Crate(String tableId, String stringId, String itemName, int color, boolean glow,
                        int passive, int monster, int boss) {

        /** The weight this crate has for the entity class a kill falls into. */
        public int weight(boolean bossKill, boolean monsterKill) {
            return bossKill ? boss : monsterKill ? monster : passive;
        }
    }

    /** File-level crate settings from {@code settings.loot_crates}; the FTB Quests names are read as fallbacks. */
    public record Settings(boolean enabled, int noDropPassive, int noDropMonster, int noDropBoss) {

        public int noDrop(boolean bossKill, boolean monsterKill) {
            return bossKill ? noDropBoss : monsterKill ? noDropMonster : noDropPassive;
        }
    }

    public static final Settings DEFAULT_SETTINGS = new Settings(false, 4000, 600, 0);

    private record Defaults(int color, int passive, int monster, int boss, boolean glow) {
    }

    private static final Map<String, Defaults> DEFAULTS = Map.of(
            "common", new Defaults(9607578, 350, 10, 0, false),
            "uncommon", new Defaults(3648105, 200, 90, 0, false),
            "rare", new Defaults(38143, 50, 200, 0, false),
            "epic", new Defaults(8388863, 9, 10, 10, false),
            "legendary", new Defaults(16761159, 1, 1, 190, true));

    /** The crate of one table, or empty when the table does not define one. */
    public static Optional<Crate> crate(String tableId, JsonObject table) {
        if (table == null || !table.has("loot_crate") || !table.get("loot_crate").isJsonObject()) {
            return Optional.empty();
        }
        JsonObject raw = table.getAsJsonObject("loot_crate");
        String stringId = string(raw, "string_id");
        if (stringId.isEmpty()) {
            stringId = slug(tableId);
        }
        Defaults defaults = DEFAULTS.get(stringId.toLowerCase(Locale.ROOT));
        Crate base = new Crate(
                tableId,
                stringId,
                string(raw, "item_name"),
                defaults == null ? WHITE : defaults.color(),
                defaults != null && defaults.glow(),
                defaults == null ? 0 : defaults.passive(),
                defaults == null ? 0 : defaults.monster(),
                defaults == null ? 0 : defaults.boss());
        return Optional.of(withWritten(base, raw));
    }

    /** Colour, glow and drop weights the table wrote on top of the string id's defaults. */
    private static Crate withWritten(Crate crate, JsonObject raw) {
        JsonObject drops = raw.has("drops") && raw.get("drops").isJsonObject()
                ? raw.getAsJsonObject("drops") : new JsonObject();
        return new Crate(crate.tableId(), crate.stringId(), crate.itemName(),
                number(raw, "color", crate.color()),
                bool(raw, "glow", crate.glow()),
                drops(drops, "passive", crate.passive()),
                drops(drops, "monster", crate.monster()),
                drops(drops, "boss", crate.boss()));
    }

    public static Settings settings(JsonObject settings) {
        if (settings == null) {
            return DEFAULT_SETTINGS;
        }
        JsonObject crates = settings.has("loot_crates") && settings.get("loot_crates").isJsonObject()
                ? settings.getAsJsonObject("loot_crates") : new JsonObject();
        JsonObject noDrop = crates.has("no_drop") && crates.get("no_drop").isJsonObject()
                ? crates.getAsJsonObject("no_drop") : new JsonObject();
        // imported FTB Quests books write the two names at the top level of data.snbt
        if (noDrop.size() == 0 && settings.has("loot_crate_no_drop")
                && settings.get("loot_crate_no_drop").isJsonObject()) {
            noDrop = settings.getAsJsonObject("loot_crate_no_drop");
        }
        return new Settings(
                bool(crates, "enabled", bool(settings, "drop_loot_crates", false)),
                dropNumber(noDrop, "passive", DEFAULT_SETTINGS.noDropPassive()),
                dropNumber(noDrop, "monster", DEFAULT_SETTINGS.noDropMonster()),
                dropNumber(noDrop, "boss", DEFAULT_SETTINGS.noDropBoss()));
    }

    /**
     * Picks the crate a kill drops, or empty for "nothing". {@code noDrop} is an entry in the same draw, exactly as
     * FTB Quests' {@code loot_crate_no_drop}: the returned table id is the crate, and empty means the no-drop weight
     * won (or every weight was zero).
     */
    public static Optional<String> choose(Map<String, Crate> crates, Settings settings, boolean bossKill,
                                          boolean monsterKill, DoubleSupplier random) {
        LinkedHashMap<String, Integer> weights = new LinkedHashMap<>();
        long total = Math.max(0, settings.noDrop(bossKill, monsterKill));
        for (Map.Entry<String, Crate> e : crates.entrySet()) {
            int weight = Math.max(0, e.getValue().weight(bossKill, monsterKill));
            weights.put(e.getKey(), weight);
            total += weight;
        }
        if (total <= 0) {
            return Optional.empty();
        }
        double threshold = Math.max(0, Math.min(1, random.getAsDouble())) * total;
        double current = Math.max(0, settings.noDrop(bossKill, monsterKill));
        if (current > threshold) {
            return Optional.empty();
        }
        for (Map.Entry<String, Integer> e : weights.entrySet()) {
            current += e.getValue();
            if (current > threshold) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /** A crate id in the form FTB Quests allows ({@code [a-z0-9_]+}), from a table id or a title. */
    public static String slug(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return s.replaceAll("_{2,}", "_");
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isString()
                ? o.get(key).getAsString() : "";
    }

    private static int drops(JsonObject o, String key, int fallback) {
        return Math.max(0, number(o, key, fallback));
    }

    private static int dropNumber(JsonObject o, String key, int fallback) {
        return Math.max(0, number(o, key, fallback));
    }

    private static int number(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        if (e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt();
        }
        if (e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean() ? 1 : 0;
        }
        String text = e.getAsString().trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        } else if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
        }
        try {
            return text.length() == 6 ? Integer.parseInt(text, 16) : Integer.parseInt(text);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return e.getAsBoolean();
    }
}
