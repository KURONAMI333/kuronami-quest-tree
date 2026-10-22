package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The belt keeps its share of a tile at every zoom (2026-10-22, the player: it looked the same size on screen). */
class BeltMetricsTest {

    private static final float TILE = 20f;

    @Test
    void theBeltIsTheSameShareOfATileAtEveryZoom() {
        for (float zoom : new float[]{0.5f, 0.6f, 0.83f, 1f, 1.25f, 2f, 3f}) {
            assertEquals(0.3f, BeltMetrics.width(TILE, zoom) / TILE, 1e-5f, "zoom " + zoom);
        }
    }

    @Test
    void theBeltGrowsOnScreenWithTheZoomLikeTheTiles() {
        for (float zoom : new float[]{0.5f, 0.75f, 1f, 2f, 3f}) {
            assertEquals(6f * zoom, BeltMetrics.width(TILE, zoom) * zoom, 1e-5f, "zoom " + zoom);
        }
    }

    @Test
    void theFloorOnlyCoversTheTightestZoom() {
        assertEquals(2.5f, BeltMetrics.width(TILE, 0.3f) * 0.3f, 1e-5f);
        assertEquals(0.5f, BeltMetrics.casing(TILE, 0.3f) * 0.3f, 1e-5f);
        assertTrue(BeltMetrics.width(TILE, 0.3f) > TILE * BeltMetrics.WIDTH_OF_TILE);
        assertTrue(BeltMetrics.width(TILE, 0.45f) <= TILE * BeltMetrics.WIDTH_OF_TILE + 1e-5f);
    }

    @Test
    void theCasingKeepsTheSameShareOfTheBelt() {
        for (float zoom : new float[]{0.7f, 1f, 2f, 3f}) {
            assertEquals(0.125f, BeltMetrics.casing(TILE, zoom) / BeltMetrics.width(TILE, zoom), 1e-5f,
                    "zoom " + zoom);
        }
    }
}
