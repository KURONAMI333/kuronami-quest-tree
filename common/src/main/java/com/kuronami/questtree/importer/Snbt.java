package com.kuronami.questtree.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Reader for FTB Library's "pretty SNBT" (the format of FTB Quests files): newline separated entries, optional commas,
 * numeric suffixes (b s L f d), typed arrays ([I; ...]). Produces Gson values so the converters can work on JSON.
 * Vanilla's TagParser cannot be used because it requires commas between entries.
 */
public final class Snbt {

    private final String s;
    private int i;

    private Snbt(String s) {
        this.s = s;
    }

    /** Parses one SNBT document. */
    public static JsonElement parse(String text) {
        Snbt p = new Snbt(text);
        JsonElement v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing data at " + p.i);
        }
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ',') {
                i++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        ws();
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        return s.charAt(i);
    }

    private JsonElement value() {
        char c = peek();
        if (c == '{') {
            return compound();
        }
        if (c == '[') {
            return list();
        }
        if (c == '"' || c == '\'') {
            return new JsonPrimitive(string());
        }
        return scalar(bare());
    }

    private JsonObject compound() {
        i++;
        JsonObject out = new JsonObject();
        while (true) {
            char c = peek();
            if (c == '}') {
                i++;
                return out;
            }
            String key = (c == '"' || c == '\'') ? string() : bare();
            if (peek() != ':') {
                throw new IllegalArgumentException("expected ':' after " + key + " at " + i);
            }
            i++;
            out.add(key, value());
        }
    }

    private JsonArray list() {
        i++;
        ws();
        if (i + 1 < s.length() && s.charAt(i + 1) == ';' && "IBL".indexOf(s.charAt(i)) >= 0) {
            i += 2;
        }
        JsonArray out = new JsonArray();
        while (true) {
            if (peek() == ']') {
                i++;
                return out;
            }
            out.add(value());
        }
    }

    private String string() {
        char q = s.charAt(i++);
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i);
            if (c == '\\') {
                char e = s.charAt(i + 1);
                b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                i += 2;
                continue;
            }
            i++;
            if (c == q) {
                return b.toString();
            }
            b.append(c);
        }
    }

    private String bare() {
        int j = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+') {
                i++;
            } else {
                break;
            }
        }
        if (j == i) {
            throw new IllegalArgumentException("unexpected '" + s.charAt(i) + "' at " + i);
        }
        return s.substring(j, i);
    }

    private static JsonElement scalar(String tok) {
        if (tok.equals("true")) {
            return new JsonPrimitive(true);
        }
        if (tok.equals("false")) {
            return new JsonPrimitive(false);
        }
        String t = tok;
        char last = tok.charAt(tok.length() - 1);
        if (tok.length() > 1 && "bBsSlLfFdD".indexOf(last) >= 0) {
            char prev = tok.charAt(tok.length() - 2);
            if (Character.isDigit(prev) || prev == '.') {
                t = tok.substring(0, tok.length() - 1);
            }
        }
        try {
            return new JsonPrimitive(Long.parseLong(t));
        } catch (NumberFormatException ignored) {
            // not an integer
        }
        try {
            double d = Double.parseDouble(t);
            // Gson refuses NaN/Infinity; keep such bare words as text
            return Double.isFinite(d) ? new JsonPrimitive(d) : new JsonPrimitive(tok);
        } catch (NumberFormatException ignored) {
            // not a number
        }
        return tok.isEmpty() ? JsonNull.INSTANCE : new JsonPrimitive(tok);
    }
}
