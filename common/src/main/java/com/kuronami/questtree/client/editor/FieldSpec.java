package com.kuronami.questtree.client.editor;

import java.util.List;

/**
 * One editable field, said in JSON paths rather than in widgets (2026-10-21, phase 3). The schema is the only place
 * that knows which task or reward type has which fields; the forms and the tests read it, so a field cannot exist in
 * one screen and be missing from the round-trip test.
 *
 * @param path      a dotted path inside the object this row edits ({@code item.id}, {@code visibility.hidden_until},
 *                  {@code position[1]}); a form prefixes it with the array slot it sits in
 * @param kind      the control and the JSON shape it writes
 * @param label     translation key for the row's label
 * @param options   values for {@link Kind#ENUM}, in cycle order, where "" means "not written"; empty for other kinds
 * @param defaultOn what a {@link Kind#BOOL} shows while the member is absent (the runtime's own default)
 */
public record FieldSpec(String path, Kind kind, String label, List<String> options, boolean defaultOn) {

    public enum Kind {
        TEXT, MULTILINE, INT, DOUBLE, BOOL,
        /** {@code true} or an object means on; the member is an object once a child field is written. */
        FLAG_OBJECT,
        ENUM, ITEM, ENTITY, DIMENSION, BIOME, STRUCTURE, ADVANCEMENT
    }

    public static FieldSpec text(String path, String label) {
        return new FieldSpec(path, Kind.TEXT, label, List.of(), false);
    }

    public static FieldSpec multiline(String path, String label) {
        return new FieldSpec(path, Kind.MULTILINE, label, List.of(), false);
    }

    public static FieldSpec integer(String path, String label) {
        return new FieldSpec(path, Kind.INT, label, List.of(), false);
    }

    public static FieldSpec decimal(String path, String label) {
        return new FieldSpec(path, Kind.DOUBLE, label, List.of(), false);
    }

    public static FieldSpec bool(String path, String label) {
        return new FieldSpec(path, Kind.BOOL, label, List.of(), false);
    }

    public static FieldSpec bool(String path, String label, boolean defaultOn) {
        return new FieldSpec(path, Kind.BOOL, label, List.of(), defaultOn);
    }

    public static FieldSpec flag(String path, String label) {
        return new FieldSpec(path, Kind.FLAG_OBJECT, label, List.of(), false);
    }

    public static FieldSpec choice(String path, String label, String... values) {
        return new FieldSpec(path, Kind.ENUM, label, List.of(values), false);
    }

    public static FieldSpec picker(String path, String label, Kind kind) {
        return new FieldSpec(path, kind, label, List.of(), false);
    }

    /** The same row one level down: a task's fields inside {@code tasks[2]}, an entry's reward inside {@code entries[0]}. */
    public FieldSpec under(String prefix) {
        return new FieldSpec(prefix + path, kind, label, options, defaultOn);
    }

    /** A picker field is also typeable: a registry entry a client does not list still reaches the JSON. */
    public boolean picker() {
        return kind == Kind.ITEM || kind == Kind.ENTITY || kind == Kind.DIMENSION
                || kind == Kind.BIOME || kind == Kind.STRUCTURE || kind == Kind.ADVANCEMENT;
    }
}
