package com.kuronami.questtree.client.edit;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which nodes the editor has picked (2026-10-20, phase 2).
 * <p>
 * A plain ordered set is enough: clicks add or replace, Ctrl toggles, a box replaces or adds, Ctrl+A takes the chapter.
 * Keeping the insertion order means a copy keeps the author's click order in menus and links. No Minecraft types, so
 * the rules are tested without a client.
 */
public final class EditorSelection {

    /** A node's rectangle on the map, in grid units. */
    public record Hit(String id, double x, double y, double halfW, double halfH) {
    }

    /** A box in grid units; {@link #of} sorts the corners. */
    public record Rect(double x0, double y0, double x1, double y1) {
        public static Rect of(double ax, double ay, double bx, double by) {
            return new Rect(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
        }

        public boolean overlaps(Hit h) {
            return h.x + h.halfW() >= x0 && h.x - h.halfW() <= x1
                    && h.y + h.halfH() >= y0 && h.y - h.halfH() <= y1;
        }
    }

    private final Set<String> ids = new LinkedHashSet<>();

    public boolean contains(String id) {
        return ids.contains(id);
    }

    public int size() {
        return ids.size();
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    public List<String> ids() {
        return List.copyOf(ids);
    }

    /** A click on a node: without Ctrl it is the whole selection; with Ctrl it joins or leaves it. */
    public void click(String id, boolean additive) {
        if (!additive) {
            ids.clear();
            ids.add(id);
        } else if (!ids.remove(id)) {
            ids.add(id);
        }
    }

    public void add(String id) {
        ids.add(id);
    }

    public void remove(String id) {
        ids.remove(id);
    }

    public void clear() {
        ids.clear();
    }

    public void selectAll(Collection<String> visible) {
        ids.clear();
        ids.addAll(visible);
    }

    /** Nodes whose tile the box touches; an additive box keeps what was already picked. */
    public void box(Rect rect, Collection<Hit> nodes, boolean additive) {
        if (!additive) {
            ids.clear();
        }
        for (Hit h : nodes) {
            if (rect.overlaps(h)) {
                ids.add(h.id());
            }
        }
    }

    /** The selection without ids the caller no longer offers (a deleted node, another chapter). */
    public void retain(Collection<String> available) {
        ids.retainAll(available);
    }

    public Set<String> view() {
        return Collections.unmodifiableSet(ids);
    }
}
