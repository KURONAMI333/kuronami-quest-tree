package com.kuronami.questtree.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectorTargetTest {

    @Test
    void readsAWholeQuest() {
        DetectorTarget target = DetectorTarget.parse("kuronami:basics/root");
        assertEquals("kuronami:basics/root", target.node());
        assertEquals(DetectorTarget.WHOLE_QUEST, target.task());
        assertFalse(target.isTask());
    }

    @Test
    void readsOneTaskOfAQuest() {
        DetectorTarget target = DetectorTarget.parse("kuronami:basics/root#2");
        assertEquals("kuronami:basics/root", target.node());
        assertEquals(2, target.task());
        assertTrue(target.isTask());
    }

    @Test
    void trimsWhitespace() {
        assertEquals("a:b/c", DetectorTarget.parse(" a:b/c#0 ").node());
    }

    @Test
    void refusesMalformedTargets() {
        assertThrows(IllegalArgumentException.class, () -> DetectorTarget.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DetectorTarget.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> DetectorTarget.parse("#1"));
        assertThrows(IllegalArgumentException.class, () -> DetectorTarget.parse("a:b/c#"));
        assertThrows(IllegalArgumentException.class, () -> DetectorTarget.parse("a:b/c#x"));
        assertThrows(IllegalArgumentException.class, () -> DetectorTarget.parse("a:b/c#-1"));
        assertThrows(IllegalArgumentException.class, () -> DetectorTarget.parse("a:b/c#1#2"));
    }

    @Test
    void refusesNegativeTaskIndex() {
        assertThrows(IllegalArgumentException.class, () -> DetectorTarget.task("a:b/c", -2));
    }
}
