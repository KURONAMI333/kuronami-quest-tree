package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextFitTest {

    @Test
    void wrappingRoomFollowsTheTextScale() {
        assertEquals(100, TextFit.room(120, 1.2f));
        assertEquals(125, TextFit.room(100, 0.8f));
        assertEquals(120, TextFit.room(120, 1f));
        assertEquals(120, TextFit.width(100, 1.2f));
        assertEquals(80, TextFit.width(100, 0.8f));
        assertEquals(1, TextFit.room(0, 1f));
    }
}
