package com.kuronami.questtree.progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A reward command cannot run above the server's cap, and never above 4 (2026-10-22 release review). */
class RewardPermissionTest {

    @Test
    void aPackCannotAskAboveTheServerCap() {
        assertEquals(2, RewardPermission.cap(4, 2), "the default cap keeps a level-4 request at 2");
        assertEquals(2, RewardPermission.cap(2, 4));
        assertEquals(3, RewardPermission.cap(3, 3));
    }

    @Test
    void theResultStaysInsideTheLevelsACommandSourceTakes() {
        assertEquals(0, RewardPermission.cap(-1, 2));
        assertEquals(4, RewardPermission.cap(4, 99));
        assertEquals(2, RewardPermission.DEFAULT);
    }
}
