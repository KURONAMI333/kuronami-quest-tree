package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map only draws what is on screen (2026-11-01): a chapter of several hundred quests must not draw its whole map
 * every frame. The rules are plain arithmetic, so they are checked here without the game.
 */
class MapCullTest {

    /** A 320x200 screen, with the map's origin at (0, 0) and one map unit per screen pixel. */
    private static final double LEFT = 0;
    private static final double TOP = 0;
    private static final double RIGHT = 320;
    private static final double BOTTOM = 200;

    @Test
    void aTileInsideTheViewIsDrawn() {
        assertTrue(MapCull.rectVisible(40, 40, 60, 60, LEFT, TOP, RIGHT, BOTTOM));
    }

    @Test
    void aTileStraddlingAnEdgeIsDrawn() {
        assertTrue(MapCull.rectVisible(-10, 40, 10, 60, LEFT, TOP, RIGHT, BOTTOM));
        assertTrue(MapCull.rectVisible(310, 190, 400, 260, LEFT, TOP, RIGHT, BOTTOM));
    }

    @Test
    void aTileTouchingAnEdgeIsDrawn() {
        // a tile whose right edge sits exactly on the screen's left edge still has that column to shade
        assertTrue(MapCull.rectVisible(-20, 40, 0, 60, LEFT, TOP, RIGHT, BOTTOM));
    }

    @Test
    void aTileBeyondTheViewOnAnySideIsLeftOut() {
        assertFalse(MapCull.rectVisible(-60, 40, -20, 60, LEFT, TOP, RIGHT, BOTTOM), "left");
        assertFalse(MapCull.rectVisible(340, 40, 380, 60, LEFT, TOP, RIGHT, BOTTOM), "right");
        assertFalse(MapCull.rectVisible(40, -60, 60, -20, LEFT, TOP, RIGHT, BOTTOM), "above");
        assertFalse(MapCull.rectVisible(40, 220, 60, 260, LEFT, TOP, RIGHT, BOTTOM), "below");
    }

    @Test
    void aLineWhoseEndsAreBothOutsideIsDrawnWhileItCrossesTheView() {
        assertTrue(MapCull.segmentVisible(-50, 100, 370, 100, LEFT, TOP, RIGHT, BOTTOM, 0), "across");
        assertTrue(MapCull.segmentVisible(160, -50, 160, 250, LEFT, TOP, RIGHT, BOTTOM, 0), "down");
        assertTrue(MapCull.segmentVisible(-50, -50, 370, 250, LEFT, TOP, RIGHT, BOTTOM, 0), "diagonal");
    }

    @Test
    void aLineThatMissesTheViewIsLeftOut() {
        assertFalse(MapCull.segmentVisible(-50, -50, 370, -20, LEFT, TOP, RIGHT, BOTTOM, 0), "above the view");
        assertFalse(MapCull.segmentVisible(-50, 100, -10, 150, LEFT, TOP, RIGHT, BOTTOM, 0), "left of the view");
        assertFalse(MapCull.segmentVisible(400, 400, 500, 500, LEFT, TOP, RIGHT, BOTTOM, 0), "far away");
        // it would cross the view's corner region, but only outside it
        assertFalse(MapCull.segmentVisible(-50, 150, -20, 250, LEFT, TOP, RIGHT, BOTTOM, 0), "past the corner");
    }

    @Test
    void aLineEndingInsideTheViewIsDrawn() {
        assertTrue(MapCull.segmentVisible(10, 10, 100, 100, LEFT, TOP, RIGHT, BOTTOM, 0));
        assertTrue(MapCull.segmentVisible(-100, -100, 10, 10, LEFT, TOP, RIGHT, BOTTOM, 0));
    }

    @Test
    void thePadKeepsAWideLineWhoseMiddleMissesTheView() {
        // a belt running 2 px above the top edge: only its width reaches onto the screen
        assertTrue(MapCull.segmentVisible(-50, -2, 370, -2, LEFT, TOP, RIGHT, BOTTOM, 3));
        assertFalse(MapCull.segmentVisible(-50, -2, 370, -2, LEFT, TOP, RIGHT, BOTTOM, 1));
    }

    @Test
    void aDotIsJudgedLikeAnyOtherSegment() {
        assertTrue(MapCull.segmentVisible(100, 100, 100, 100, LEFT, TOP, RIGHT, BOTTOM, 0));
        assertFalse(MapCull.segmentVisible(300, -10, 300, -10, LEFT, TOP, RIGHT, BOTTOM, 0));
    }

    @Test
    void theViewFollowsThePanAndZoom() {
        // the map is drawn at half size with its origin 40 px right of the screen's left edge
        double zoom = 0.5;
        double offset = 40;
        double viewLeft = MapCull.toMap(0, offset, zoom);
        double viewTop = MapCull.toMap(0, 0, zoom);
        double viewRight = MapCull.toMap(320, offset, zoom);
        double viewBottom = MapCull.toMap(200, 0, zoom);
        // map 80,80 lands at screen 80,40, inside the view
        assertTrue(MapCull.rectVisible(70, 70, 90, 90, viewLeft, viewTop, viewRight, viewBottom));
        // map -400,80 lands far left of the screen
        assertFalse(MapCull.rectVisible(-410, 70, -390, 90, viewLeft, viewTop, viewRight, viewBottom));
    }
}
