package com.kuronami.questtree.client.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GridSnapTest {

    @Test
    void snapsToHalfUnits() {
        assertEquals(1.0, GridSnap.snap(0.8));
        assertEquals(0.5, GridSnap.snap(0.3));
        assertEquals(-1.5, GridSnap.snap(-1.3));
        assertEquals(2.25, GridSnap.snap(2.25, false));
    }

    @Test
    void dragDeltaIsInGridUnitsAtTheCurrentZoom() {
        // 24 GUI px per unit: 36 px at zoom 2 is 0.75 units, which snaps to 1
        assertEquals(1.0, GridSnap.dragDelta(36, 2f, 24, true));
        assertEquals(0.75, GridSnap.dragDelta(36, 2f, 24, false), 1e-9);
        assertEquals(0.5, GridSnap.dragDelta(12, 1f, 24, true));
    }

    @Test
    void nudgeMovesByWholeSteps() {
        assertArrayEquals(new double[]{1, 2}, GridSnap.nudge(0, 1, 1, 1, 1));
        assertArrayEquals(new double[]{-4, 5}, GridSnap.nudge(1, 5, -1, 0, 5));
    }
}
