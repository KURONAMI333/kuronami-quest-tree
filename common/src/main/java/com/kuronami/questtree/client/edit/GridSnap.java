package com.kuronami.questtree.client.edit;

/** Grid arithmetic for moving nodes (2026-10-20, phase 2): FTB Quests positions, half-unit snap, arrow nudges. */
public final class GridSnap {

    /** The default snap: half a grid unit, fine enough for a tidy tree and coarse enough to settle by eye. */
    public static final double STEP = 0.5;

    private GridSnap() {
    }

    /** The nearest half unit. */
    public static double snap(double value) {
        return Math.round(value / STEP) * STEP;
    }

    /** The nearest half unit unless snapping is off (Alt while dragging). */
    public static double snap(double value, boolean enabled) {
        return enabled ? snap(value) : value;
    }

    /** Pointer movement in GUI px as grid units at the current zoom. */
    public static double pixelsToUnits(double pixels, float zoom, int unit) {
        return pixels / (unit * Math.max(0.01, zoom));
    }

    /** How far a drag moved on the grid, as a whole number of snap steps (a node keeps its exact offset when Alt is held). */
    public static double dragDelta(double pixels, float zoom, int unit, boolean enabled) {
        double raw = pixelsToUnits(pixels, zoom, unit);
        return enabled ? snap(raw) : raw;
    }

    /** One arrow-key nudge, in grid units; Step 5 makes a fast move without a drag. */
    public static double[] nudge(double x, double y, int dx, int dy, int unitsPerStep) {
        return new double[]{x + dx * unitsPerStep, y + dy * unitsPerStep};
    }
}
