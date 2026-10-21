package com.kuronami.questtree.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An observation task writes and announces under the same rules as a normal bump. */
class ObservationWriteTest {

    @Test
    void conditionsGateTheWrite() {
        assertTrue(ObservationWrite.writes(2, 3, true));
        assertFalse(ObservationWrite.writes(2, 3, false), "a task whose conditions fail keeps its old progress");
        assertFalse(ObservationWrite.writes(3, 3, true), "an unchanged run is not written again");
    }

    @Test
    void taskIsAnnouncedOnlyOnTheFirstReach() {
        assertTrue(ObservationWrite.announces(4, 5, 5, true));
        assertFalse(ObservationWrite.announces(5, 5, 5, true), "a run already at the target is not announced again");
        assertFalse(ObservationWrite.announces(4, 5, 5, false), "a task whose conditions fail announces nothing");
    }

    @Test
    void aQuietTickDoesNotScanEveryNode() {
        assertFalse(ObservationWrite.rechecks(3, 3, 5, true), "an unchanged run writes nothing, so nothing rescans");
        assertFalse(ObservationWrite.rechecks(3, 4, 5, false), "a task whose conditions fail is not rescanned");
        assertFalse(ObservationWrite.rechecks(3, 4, 5, true), "a write below the target cannot complete the node");
        assertTrue(ObservationWrite.rechecks(4, 5, 5, true));
    }
}
