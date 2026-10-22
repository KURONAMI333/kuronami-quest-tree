package com.kuronami.questtree.server;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A dead or discarded giver loses its link; a chunk unload does not (2026-10-22 review, L5). */
class GiverRemovalTest {

    @Test
    void onlyAGoneEntityForgetsItsLink() {
        assertTrue(GiverService.forgetsLink(Entity.RemovalReason.KILLED));
        assertTrue(GiverService.forgetsLink(Entity.RemovalReason.DISCARDED));
        assertFalse(GiverService.forgetsLink(Entity.RemovalReason.UNLOADED_TO_CHUNK));
        assertFalse(GiverService.forgetsLink(Entity.RemovalReason.UNLOADED_WITH_PLAYER));
        assertFalse(GiverService.forgetsLink(Entity.RemovalReason.CHANGED_DIMENSION));
    }
}
