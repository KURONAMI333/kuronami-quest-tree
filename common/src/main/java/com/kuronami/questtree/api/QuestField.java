package com.kuronami.questtree.api;

import java.util.List;

/**
 * One editable member of a task or reward type, declared by the mod that supplies the type so the in-game editor can
 * draw a row for it ({@code QuestTreeApi.describeTaskType}). Plain data with no Minecraft types, so a common-side
 * registration and its tests can both use it.
 * <p>
 * The editor writes {@code path} the same way it writes its own fields: dotted, with array indexes in brackets
 * ({@code count}, {@code item.id}, {@code drops[0].chance}). A blank value clears the member, a bool takes
 * {@code true}/{@code false}, and a picker kind can also be typed, so an id the client does not list still reaches the
 * JSON.
 * <p>
 * The members every type shares ({@code type}, {@code title}, {@code icon}, {@code optional}, {@code text},
 * {@code team}, {@code auto}, {@code exclude_from_claim_all}, {@code mode}, {@code imported_unmapped}) are already
 * drawn by the editor, so a declaration for one of them is ignored.
 * <h2>Example</h2>
 * <pre>{@code
 * QuestTreeApi.describeTaskType(id, List.of(
 *         QuestField.integer("count", "example.field.count"),
 *         QuestField.choice("scope", "example.field.scope", "self", "team")));
 * }</pre>
 *
 * @param path    the member this row edits inside the task or reward object; must not be blank
 * @param kind    the control and the JSON shape it writes
 * @param label   translation key for the row's label; a blank label shows the path
 * @param options values for {@link Kind#ENUM}, in cycle order, where "" means "not written"; empty for other kinds
 */
public record QuestField(String path, Kind kind, String label, List<String> options) {

    /** The shapes a declared field can take; the same controls the editor's own fields use. */
    public enum Kind {
        TEXT, MULTILINE, INT, DECIMAL, BOOL, ENUM, ITEM, ENTITY, DIMENSION, BIOME, STRUCTURE, ADVANCEMENT,
        /** A JSON array of ids, shown as a comma-separated line. */
        IDS,
        /** A JSON value shown as compact JSON text (an object, an array, a number); unreadable text is not written. */
        JSON
    }

    public QuestField {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A quest field needs a path");
        }
        path = path.trim();
        // a lang key nobody ships would draw a row with no name; the path itself is the readable fallback
        label = label == null || label.isBlank() ? path : label;
        options = options == null ? List.of() : List.copyOf(options);
    }

    public static QuestField text(String path, String label) {
        return new QuestField(path, Kind.TEXT, label, List.of());
    }

    public static QuestField multiline(String path, String label) {
        return new QuestField(path, Kind.MULTILINE, label, List.of());
    }

    public static QuestField integer(String path, String label) {
        return new QuestField(path, Kind.INT, label, List.of());
    }

    public static QuestField decimal(String path, String label) {
        return new QuestField(path, Kind.DECIMAL, label, List.of());
    }

    public static QuestField bool(String path, String label) {
        return new QuestField(path, Kind.BOOL, label, List.of());
    }

    /** A fixed set of values, cycled in the order given; "" means the member is not written. */
    public static QuestField choice(String path, String label, String... values) {
        return new QuestField(path, Kind.ENUM, label, List.of(values));
    }

    /** An id with a search list: items, entities, dimensions, biomes, structures or advancements. */
    public static QuestField picker(String path, String label, Kind kind) {
        return new QuestField(path, kind, label, List.of());
    }

    /** A JSON array of ids, shown as a comma-separated line. */
    public static QuestField ids(String path, String label) {
        return new QuestField(path, Kind.IDS, label, List.of());
    }

    /** A free-form JSON value (an object such as a map, an array, a number), edited as compact text. */
    public static QuestField json(String path, String label) {
        return new QuestField(path, Kind.JSON, label, List.of());
    }
}
