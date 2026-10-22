package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryButtonLayoutTest {

    @Test
    void sitsCentredOverThePlayerPreview() {
        assertEquals(40, InventoryButtonLayout.x(0));
        assertEquals(240, InventoryButtonLayout.x(200));
    }

    @Test
    void sitsAtTheTopLeavingRoomForTabs() {
        assertEquals(2, InventoryButtonLayout.y(100));
        assertTrue(InventoryButtonLayout.fits(48));
        assertTrue(!InventoryButtonLayout.fits(47));
    }
}
