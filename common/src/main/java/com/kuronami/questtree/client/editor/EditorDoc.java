package com.kuronami.questtree.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes one field by path, and builds the RFC 7396 patch a save sends (2026-10-21, phase 3).
 * <p>
 * Paths are dotted with array indexes in brackets: {@code item.id}, {@code visibility.hidden_until},
 * {@code position[1]}. Writes create only the objects and arrays the path needs, and a blank value removes the member:
 * an empty field means "let the pack's value (or the default) show", not an empty string in the data.
 */
public final class EditorDoc {

    private EditorDoc() {
    }

    public static JsonElement get(JsonObject root, String path) {
        JsonElement current = root;
        for (String token : tokens(path)) {
            if (current == null) {
                return null;
            }
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray() && isIndex(token)) {
                int index = Integer.parseInt(token);
                JsonArray array = current.getAsJsonArray();
                current = index >= 0 && index < array.size() ? array.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    public static boolean has(JsonObject root, String path) {
        return get(root, path) != null;
    }

    /** A primitive's text, an item object's id, or "" when the path is absent or not a value a field can show. */
    public static String text(JsonObject root, String path) {
        JsonElement e = get(root, path);
        if (e == null || e.isJsonNull()) {
            return "";
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("id") && o.get("id").isJsonPrimitive()) {
                return o.get("id").getAsString();
            }
            if (o.has("tag") && o.get("tag").isJsonPrimitive()) {
                return "#" + o.get("tag").getAsString();
            }
        }
        return "";
    }

    public static int integer(JsonObject root, String path, int fallback) {
        Double d = decimal(root, path);
        return d == null ? fallback : (int) Math.round(d);
    }

    public static Double decimal(JsonObject root, String path) {
        JsonElement e = get(root, path);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static boolean bool(JsonObject root, String path, boolean fallback) {
        JsonElement e = get(root, path);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return e.getAsBoolean();
    }

    public static void setString(JsonObject root, String path, String value) {
        if (value == null || value.isBlank()) {
            remove(root, path);
        } else {
            set(root, path, new JsonPrimitive(value.trim()));
        }
    }

    public static void setNumber(JsonObject root, String path, String value, boolean integer) {
        if (value == null || value.isBlank()) {
            clearNumber(root, path);
            return;
        }
        try {
            if (integer) {
                set(root, path, new JsonPrimitive(Long.parseLong(value.trim())));
            } else {
                double parsed = Double.parseDouble(value.trim());
                // "NaN" and "Infinity" parse, but would blank the map once the server saved them
                if (!Double.isFinite(parsed)) {
                    return;
                }
                set(root, path, new JsonPrimitive(parsed));
            }
        } catch (NumberFormatException ignored) {
            // the field shows what was typed; the JSON keeps the last number that parsed
        }
    }

    /**
     * A blank numeric field. An array element is zeroed in place instead of removed, because removing it would move
     * every element after it (a position or size has three fixed slots, not a list). Once every slot is zero the whole
     * array goes, so the pack's value or the default shows again.
     */
    private static void clearNumber(JsonObject root, String path) {
        List<String> tokens = tokens(path);
        int open = path == null ? -1 : path.lastIndexOf('[');
        if (tokens.isEmpty() || !isIndex(tokens.get(tokens.size() - 1)) || open < 0) {
            remove(root, path);
            return;
        }
        String arrayPath = path.substring(0, open);
        JsonElement parent = get(root, arrayPath);
        if (parent == null || !parent.isJsonArray()) {
            return;
        }
        JsonArray array = parent.getAsJsonArray();
        int index = Integer.parseInt(tokens.get(tokens.size() - 1));
        while (array.size() <= index) {
            array.add(new JsonPrimitive(0));
        }
        array.set(index, new JsonPrimitive(0));
        for (JsonElement e : array) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || e.getAsDouble() != 0) {
                return;
            }
        }
        remove(root, arrayPath);
    }

    public static void setBool(JsonObject root, String path, boolean value) {
        set(root, path, new JsonPrimitive(value));
    }

    /** Item ids keep the shape they found: an object with {@code id}, a {@code #tag}, or a bare string. */
    public static void setItem(JsonObject root, String path, String value) {
        if (value == null || value.isBlank()) {
            remove(root, path);
            return;
        }
        String id = value.trim();
        JsonElement existing = get(root, path);
        if (id.startsWith("#")) {
            JsonObject tag = new JsonObject();
            tag.addProperty("tag", id.substring(1));
            set(root, path, tag);
            return;
        }
        if (existing != null && existing.isJsonObject()) {
            JsonObject copy = existing.getAsJsonObject().deepCopy();
            copy.remove("tag");
            copy.addProperty("id", id);
            set(root, path, copy);
        } else if (existing != null && existing.isJsonPrimitive()) {
            set(root, path, new JsonPrimitive(id));
        } else {
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            set(root, path, item);
        }
    }

    /**
     * A flag that is {@code true} or an object (FTB Quests' {@code repeat}): on keeps an existing object with its
     * members and writes an empty one otherwise, off removes the member.
     */
    public static void setFlag(JsonObject root, String path, boolean on) {
        if (!on) {
            remove(root, path);
            return;
        }
        JsonElement existing = get(root, path);
        if (existing == null || !existing.isJsonObject()) {
            set(root, path, new JsonObject());
        }
    }

    public static boolean flag(JsonObject root, String path) {
        JsonElement e = get(root, path);
        return e != null && (e.isJsonObject() || e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean());
    }

    // ------------------------------------------------------------------ rows

    /** What a row's box shows for the current JSON. */
    public static String show(JsonObject root, FieldSpec field) {
        return switch (field.kind()) {
            case BOOL -> String.valueOf(bool(root, field.path(), field.defaultOn()));
            case FLAG_OBJECT -> String.valueOf(flag(root, field.path()));
            case INT, DOUBLE -> {
                Double d = decimal(root, field.path());
                yield d == null ? "" : field.kind() == FieldSpec.Kind.INT || d == Math.rint(d)
                        ? String.valueOf(Math.round(d)) : String.valueOf(d);
            }
            default -> text(root, field.path());
        };
    }

    /** Writes what the player typed or picked; a blank removes the member, a bool/flag takes "true" or "false". */
    public static void write(JsonObject root, FieldSpec field, String value) {
        switch (field.kind()) {
            case BOOL -> setBool(root, field.path(), Boolean.parseBoolean(value));
            case FLAG_OBJECT -> setFlag(root, field.path(), Boolean.parseBoolean(value));
            case INT -> setNumber(root, field.path(), value, true);
            case DOUBLE -> setNumber(root, field.path(), value, false);
            case ITEM -> setItem(root, field.path(), value);
            default -> setString(root, field.path(), value);
        }
    }

    // ------------------------------------------------------------------ arrays

    /** The array at {@code path}, created when absent; null when something that is not an array sits there. */
    public static JsonArray array(JsonObject root, String path, boolean create) {
        JsonElement e = get(root, path);
        if (e != null && e.isJsonArray()) {
            return e.getAsJsonArray();
        }
        if (!create || e != null && !e.isJsonNull()) {
            return null;
        }
        JsonArray made = new JsonArray();
        set(root, path, made);
        return made;
    }

    /** Moves one element by {@code delta} places; false when it would leave the array. */
    public static boolean move(JsonArray array, int index, int delta) {
        int to = index + delta;
        if (index < 0 || index >= array.size() || to < 0 || to >= array.size() || delta == 0) {
            return false;
        }
        JsonElement item = array.remove(index);
        List<JsonElement> rest = new ArrayList<>();
        for (JsonElement e : array) {
            rest.add(e);
        }
        rest.add(to, item);
        while (array.size() > 0) {
            array.remove(0);
        }
        rest.forEach(array::add);
        return true;
    }

    public static void remove(JsonObject root, String path) {
        List<String> tokens = tokens(path);
        if (tokens.isEmpty()) {
            return;
        }
        JsonElement parent = walk(root, tokens.subList(0, tokens.size() - 1), false, false);
        String last = tokens.get(tokens.size() - 1);
        if (parent == null) {
            return;
        }
        if (parent.isJsonObject()) {
            parent.getAsJsonObject().remove(last);
        } else if (parent.isJsonArray() && isIndex(last)) {
            int index = Integer.parseInt(last);
            JsonArray array = parent.getAsJsonArray();
            if (index >= 0 && index < array.size()) {
                array.remove(index);
            }
        }
    }

    private static void set(JsonObject root, String path, JsonElement value) {
        List<String> tokens = tokens(path);
        if (tokens.isEmpty()) {
            return;
        }
        JsonElement parent = walk(root, tokens.subList(0, tokens.size() - 1), true, isIndex(tokens.get(tokens.size() - 1)));
        String last = tokens.get(tokens.size() - 1);
        if (parent.isJsonObject()) {
            parent.getAsJsonObject().add(last, value);
        } else {
            JsonArray array = parent.getAsJsonArray();
            int index = Integer.parseInt(last);
            while (array.size() <= index) {
                // a position or size array gets zeros, never nulls: the validator wants three numbers
                array.add(new JsonPrimitive(0));
            }
            array.set(index, value);
        }
    }

    private static JsonElement walk(JsonObject root, List<String> tokens, boolean create, boolean lastIsArray) {
        JsonElement current = root;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean wantArray = i == tokens.size() - 1 ? lastIsArray
                    : i + 1 < tokens.size() && isIndex(tokens.get(i + 1));
            if (current.isJsonObject()) {
                JsonObject object = current.getAsJsonObject();
                JsonElement next = object.get(token);
                boolean container = next != null && (next.isJsonObject() || next.isJsonArray());
                if (!container) {
                    if (!create) {
                        return null;
                    }
                    // a child under {@code "repeat": true} turns the flag into the object form, which still reads as on
                    JsonObject made = new JsonObject();
                    if (!wantArray && next != null && next.isJsonPrimitive() && next.getAsJsonPrimitive().isString()) {
                        // an item written as a bare id keeps it when a count is added under it
                        made.addProperty("id", next.getAsString());
                    }
                    next = wantArray ? new JsonArray() : made;
                    object.add(token, next);
                }
                current = next;
            } else if (current.isJsonArray() && isIndex(token)) {
                JsonArray array = current.getAsJsonArray();
                int index = Integer.parseInt(token);
                if (!create) {
                    if (index < 0 || index >= array.size()) {
                        return null;
                    }
                    current = array.get(index);
                } else {
                    while (array.size() <= index) {
                        array.add(com.google.gson.JsonNull.INSTANCE);
                    }
                    JsonElement next = array.get(index);
                    if (next == null || next.isJsonNull() || !(next.isJsonObject() || next.isJsonArray())) {
                        next = wantArray ? new JsonArray() : new JsonObject();
                        array.set(index, next);
                    }
                    current = next;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    private static List<String> tokens(String path) {
        List<String> out = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (!token.isEmpty()) {
                    out.add(token.toString());
                    token.setLength(0);
                }
            } else if (c == '[') {
                if (!token.isEmpty()) {
                    out.add(token.toString());
                    token.setLength(0);
                }
                int close = path.indexOf(']', i);
                if (close < 0) {
                    return out;
                }
                out.add(path.substring(i + 1, close));
                i = close;
            } else if (c != ']') {
                token.append(c);
            }
        }
        if (!token.isEmpty()) {
            out.add(token.toString());
        }
        return out;
    }

    private static boolean isIndex(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ patch

    /**
     * Whether two working copies still hold the same content. A screen keeps the copy a refusal was shown for and
     * compares it with its live copy when the author presses leave again: equal means the author did not change
     * anything, so leaving discards the refused form; different means the change is still waiting to be saved.
     */
    public static boolean sameContent(JsonObject refused, JsonObject current) {
        return java.util.Objects.equals(refused, current);
    }

    /** The RFC 7396 patch from {@code original} to {@code edited}; empty when nothing changed. */
    public static JsonObject patch(JsonObject original, JsonObject edited) {
        JsonElement diff = diff(original, edited);
        return diff != null && diff.isJsonObject() ? diff.getAsJsonObject() : new JsonObject();
    }

    /** Null means "no change"; otherwise the value that replaces the old one (RFC 7396). */
    public static JsonElement diff(JsonElement original, JsonElement edited) {
        if (original == null) {
            return edited == null ? null : edited.deepCopy();
        }
        if (edited == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (original.equals(edited)) {
            return null;
        }
        if (original.isJsonObject() && edited.isJsonObject()) {
            JsonObject patch = new JsonObject();
            JsonObject a = original.getAsJsonObject();
            JsonObject b = edited.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : a.entrySet()) {
                if (!b.has(e.getKey())) {
                    patch.add(e.getKey(), com.google.gson.JsonNull.INSTANCE);
                }
            }
            for (Map.Entry<String, JsonElement> e : b.entrySet()) {
                JsonElement change = diff(a.get(e.getKey()), e.getValue());
                if (change != null) {
                    patch.add(e.getKey(), change);
                }
            }
            return patch.isEmpty() ? null : patch;
        }
        return edited.deepCopy();
    }

    /** RFC 7396 merge, the server's own rule; a null removes the member. */
    public static void mergePatch(JsonObject target, JsonObject patch) {
        for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
            JsonElement value = e.getValue();
            if (value.isJsonNull()) {
                target.remove(e.getKey());
            } else if (value.isJsonObject() && target.has(e.getKey()) && target.get(e.getKey()).isJsonObject()) {
                mergePatch(target.getAsJsonObject(e.getKey()), value.getAsJsonObject());
            } else {
                target.add(e.getKey(), value.deepCopy());
            }
        }
    }
}
