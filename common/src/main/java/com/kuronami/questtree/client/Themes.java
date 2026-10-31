package com.kuronami.questtree.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Named look themes (2026-10-31): a theme is the same {@code gui/palette.json} and {@code textures/gui/} files placed
 * under {@code themes/<id>/}, so a pack or the mod can swap the whole look without a second code path. The default
 * theme reads no theme files at all, which keeps the look exactly as it was before themes existed.
 * <p>
 * Resolution, lowest priority first: the mod's own files, then the selected theme's, then a resource pack's. A pack
 * therefore still wins over a theme, as it always won over the mod's palette. For pictures the theme also sits above
 * the mod's own copy. Only the pictures read through {@link Tiles#guiTexture} take a theme's copy: {@code ground},
 * {@code patch/tile}, {@code shape/rsquare}, {@code shape/rsquare_edge} and {@code glass_mask}; the other shapes and
 * icons always come from the mod or a pack.
 */
final class Themes {

    /** No theme layer: the mod's own files and the resource pack's, exactly as before 2026-10-31. */
    static final String DEFAULT = "default";

    /** The ids the settings screen offers, in order. */
    private static final List<String> IDS = List.of(DEFAULT, "paper");

    private Themes() {
    }

    /** The theme ids a player can choose. */
    static List<String> ids() {
        return IDS;
    }

    /**
     * The theme actually drawn: high contrast is a readability switch over everything, so it turns a theme off rather
     * than mixing its light toast and HUD colours with high contrast's white text (independent review, 2026-10-31).
     */
    static String effective(String id, boolean highContrast) {
        return highContrast ? DEFAULT : normalize(id);
    }

    /** A known id, or {@link #DEFAULT} for anything else (an old or hand-edited config value never throws). */
    static String normalize(String id) {
        return id != null && IDS.contains(id) ? id : DEFAULT;
    }

    static boolean isDefault(String id) {
        return DEFAULT.equals(normalize(id));
    }

    /** The next id in the settings list, wrapping round. */
    static String next(String id) {
        return IDS.get((IDS.indexOf(normalize(id)) + 1) % IDS.size());
    }

    /** The theme's palette file, or null for the default theme (which reads no theme layer). */
    static String palette(String id) {
        String n = normalize(id);
        return DEFAULT.equals(n) ? null : "themes/" + n + "/palette.json";
    }

    /** The theme's copy of a GUI picture ({@code path} without {@code .png}), or null for the default theme. */
    static String texture(String id, String path) {
        String n = normalize(id);
        return DEFAULT.equals(n) ? null : "themes/" + n + "/textures/gui/" + path + ".png";
    }

    /** Which layer a GUI picture is drawn from, highest priority first. */
    enum Layer { PACK, THEME, FLAT }

    /**
     * A resource pack's copy wins over everything; without one the theme's copy is used; otherwise the flat id (the
     * mod's own picture, or a missing one that draws the flat colour). The default theme never reports {@link Layer#THEME}.
     */
    static Layer layer(boolean packOverride, boolean themeHasPicture) {
        if (packOverride) {
            return Layer.PACK;
        }
        return themeHasPicture ? Layer.THEME : Layer.FLAT;
    }

    /**
     * The source id of the resource pack on top of a stack, or empty when the top layer is the mod's own built-in pack
     * or the stack is empty (2026-10-31). {@code stackSourceIds} is in resource-stack order, lowest priority first. The
     * mod's built-in pack always sits at the bottom, so a different id on top is a pack the player added. Both the
     * palette and the GUI pictures use this one rule: the mod's own file must never be mistaken for a pack's.
     */
    static Optional<String> packResourceId(List<String> stackSourceIds, String builtinSourceId) {
        if (stackSourceIds == null || stackSourceIds.isEmpty() || builtinSourceId == null) {
            return Optional.empty();
        }
        String top = stackSourceIds.get(stackSourceIds.size() - 1);
        return top == null || top.equals(builtinSourceId) ? Optional.empty() : Optional.of(top);
    }

    /**
     * The palette after the three layers: the mod's own file, then the selected theme's (skipped for the default),
     * then the resource pack's. A missing layer is an empty object, so a theme without a palette changes nothing.
     */
    static JsonObject resolve(JsonObject mod, String id, JsonObject theme, JsonObject pack) {
        JsonObject out = overlay(new JsonObject(), mod);
        if (!isDefault(id)) {
            overlay(out, theme);
        }
        overlay(out, pack);
        return out;
    }

    private static JsonObject overlay(JsonObject into, JsonObject from) {
        if (from != null) {
            for (Map.Entry<String, JsonElement> e : from.entrySet()) {
                into.add(e.getKey(), e.getValue());
            }
        }
        return into;
    }
}
