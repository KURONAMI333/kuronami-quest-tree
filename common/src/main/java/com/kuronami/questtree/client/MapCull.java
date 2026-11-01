package com.kuronami.questtree.client;

/**
 * What a frame of the map has to draw: the visible area in the map's own units, and whether a tile or a line meets it.
 * Kept free of drawing so the rules can be checked without the game (a large book has chapters of several hundred
 * quests, and only the part on screen is worth drawing).
 */
final class MapCull {

    private MapCull() {
    }

    /**
     * A screen coordinate as a map coordinate: the map's origin sits at {@code offset} on screen and one map unit is
     * {@code zoom} screen pixels wide.
     */
    static double toMap(double screen, double offset, double zoom) {
        return (screen - offset) / zoom;
    }

    /** Whether an axis-aligned rectangle meets the visible area (touching counts as meeting). */
    static boolean rectVisible(double left, double top, double right, double bottom,
                               double viewLeft, double viewTop, double viewRight, double viewBottom) {
        return right >= viewLeft && left <= viewRight && bottom >= viewTop && top <= viewBottom;
    }

    /**
     * Whether a line meets the visible area, grown by {@code pad} so a wide belt is kept while any part of it shows.
     * Both ends may be outside: a line that runs across the screen is drawn. A line that misses it on every side is
     * not. The test works on the two slabs (x and y) of the segment and never allocates.
     */
    static boolean segmentVisible(double x1, double y1, double x2, double y2,
                                  double viewLeft, double viewTop, double viewRight, double viewBottom, double pad) {
        double left = viewLeft - pad;
        double top = viewTop - pad;
        double right = viewRight + pad;
        double bottom = viewBottom + pad;
        double dx = x2 - x1;
        double dy = y2 - y1;
        double t0 = 0;
        double t1 = 1;
        if (dx == 0) {
            if (x1 < left || x1 > right) {
                return false;
            }
        } else {
            double a = (left - x1) / dx;
            double b = (right - x1) / dx;
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
            if (t0 > t1) {
                return false;
            }
        }
        if (dy == 0) {
            return y1 >= top && y1 <= bottom;
        }
        double a = (top - y1) / dy;
        double b = (bottom - y1) / dy;
        t0 = Math.max(t0, Math.min(a, b));
        t1 = Math.min(t1, Math.max(a, b));
        return t0 <= t1;
    }
}
