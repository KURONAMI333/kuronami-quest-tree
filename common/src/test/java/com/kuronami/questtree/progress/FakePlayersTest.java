package com.kuronami.questtree.progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Machine stand-ins are neither restricted nor given progress (2026-10-22 review, L3). */
class FakePlayersTest {

    @Test
    void onlyAConnectedPlainServerPlayerIsReal() {
        assertFalse(FakePlayers.isFake(true, true));
        assertTrue(FakePlayers.isFake(false, true), "no connection: Create's deployer and its kin");
        assertTrue(FakePlayers.isFake(true, false), "a mod's ServerPlayer subclass is a stand-in");
        assertTrue(FakePlayers.isFake(false, false));
    }
}
