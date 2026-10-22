package com.kuronami.questtree.client;

/**
 * Belt sizes on the quest map, kept free of drawing so the scaling can be checked.
 *
 * <p>2026-10-22 (the player, after playing the build): the belt and its chevrons looked the same size on screen at
 * every zoom, so they read thick when zoomed out and thin when zoomed in. They are drawn in map units, but the old
 * 5 GUI px floor took over from 0.83x down, which is most of the range a chapter is read at. The share of a tile is
 * kept (3/10 of a size-1 tile, the look accepted on 2026-10-20) and the floor now only covers the tightest zoom.
 */
public final class BeltMetrics {

    /** Belt width as a share of a size-1 tile, and the same for the casing down either side. */
    static final float WIDTH_OF_TILE = 0.3f;
    static final float CASING_OF_TILE = 0.0375f;
    /**
     * Smallest belt and casing on screen, in GUI pixels. The belt floor binds only below 0.42x (the player's tightest
     * manual zoom is 0.3x, where a proportional belt would be 1.8 px and the chevrons would be gone).
     */
    static final float MIN_WIDTH_PX = 2.5f;
    static final float MIN_CASING_PX = 0.5f;

    private BeltMetrics() {
    }

    /** Belt width in map units: the same share of a tile at every zoom, with a 2.5 GUI px floor. */
    public static float width(float tile, float zoom) {
        return Math.max(tile * WIDTH_OF_TILE, MIN_WIDTH_PX / zoom);
    }

    /** Casing width in map units: the same share as the belt, with a 0.5 GUI px floor. */
    public static float casing(float tile, float zoom) {
        return Math.max(tile * CASING_OF_TILE, MIN_CASING_PX / zoom);
    }
}
