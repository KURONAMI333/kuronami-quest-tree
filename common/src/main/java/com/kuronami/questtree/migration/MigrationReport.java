package com.kuronami.questtree.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a progress migration found, wrote and could not map (2026-10-20). The same object is shown in chat (counts and
 * the first {@link #CHAT_UNRESOLVED} unresolved ids) and written in full to the report file, so an admin can compare a
 * dry run with the real run: a counter named {@code *_added} is what the run changed, {@code *_found} what it resolved
 * in the source, so a second apply reads zero for every {@code *_added}.
 */
public final class MigrationReport {

    public static final int CHAT_UNRESOLVED = 20;

    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Set<String> unresolved = new LinkedHashSet<>();
    private final Map<String, Integer> unresolvedByKind = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    public void add(String key, int n) {
        counts.merge(key, n, Integer::sum);
    }

    public int get(String key) {
        return counts.getOrDefault(key, 0);
    }

    public Map<String, Integer> counts() {
        return counts;
    }

    /** Records an id that could not be mapped. The same id found twice is listed once. */
    public void unresolved(String kind, String what) {
        if (unresolved.add(kind + " " + what)) {
            unresolvedByKind.merge(kind, 1, Integer::sum);
            counts.merge("unresolved", 1, Integer::sum);
        }
    }

    public List<String> unresolvedList() {
        return new ArrayList<>(unresolved);
    }

    public Map<String, Integer> unresolvedByKind() {
        return unresolvedByKind;
    }

    public void note(String text) {
        notes.add(text);
    }

    public List<String> notes() {
        return notes;
    }

    /** The report file's text: header, every counter, then every unresolved id. */
    public String toText(String title, boolean applied, String stamp) {
        StringBuilder b = new StringBuilder();
        b.append(title).append(" (").append(applied ? "apply" : "dry_run").append(") ").append(stamp).append('\n');
        counts.forEach((k, v) -> b.append(k).append(": ").append(v).append('\n'));
        notes.forEach(n -> b.append("note: ").append(n).append('\n'));
        if (!unresolved.isEmpty()) {
            b.append("unresolved (").append(unresolved.size()).append("):\n");
            unresolvedByKind.forEach((k, v) -> b.append("  ").append(k).append(": ").append(v).append('\n'));
            unresolved.forEach(u -> b.append("  ").append(u).append('\n'));
        }
        return b.toString();
    }
}
