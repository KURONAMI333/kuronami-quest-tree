package com.kuronami.questtree.client.edit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The optimistic side of a move (2026-10-20, phase 2): where every dragged node sat before the drop, and where it now
 * asks the server to be. The map draws the target at once; a refusal puts the originals back as one step, not node by
 * node. Pure so the rollback rule is tested without a client.
 */
public final class MoveBatch {

    private final Map<String, double[]> original;
    private final Map<String, double[]> target;
    private boolean refused;

    public MoveBatch(Map<String, double[]> original, Map<String, double[]> target) {
        this.original = copy(original);
        this.target = copy(target);
    }

    public boolean isEmpty() {
        return target.isEmpty();
    }

    public Map<String, double[]> original() {
        return copy(original);
    }

    public Map<String, double[]> target() {
        return copy(target);
    }

    /** Show the targets over the synced positions while the requests are in flight. */
    public void apply(Map<String, double[]> overlay) {
        overlay.putAll(copy(target));
    }

    /** One request was refused; true the first time, so a batch rolls back once and not once per node. */
    public boolean refuse() {
        if (refused) {
            return false;
        }
        refused = true;
        return true;
    }

    /** Put the batch back: the overlay shows where the server still has the nodes. */
    public void rollback(Map<String, double[]> overlay) {
        overlay.putAll(copy(original));
    }

    private static Map<String, double[]> copy(Map<String, double[]> from) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : from.entrySet()) {
            out.put(e.getKey(), e.getValue().clone());
        }
        return out;
    }
}
