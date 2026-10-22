package com.kuronami.questtree.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BarrierTeleportTest {

    @Test
    void absoluteDestinationIgnoresTheBarrierPosition() {
        BarrierTeleport teleport = BarrierTeleport.absolute("minecraft:the_nether", 10, 64, -3, 90F, 0F);
        assertEquals(10.5D, teleport.destX(100));
        assertEquals(64D, teleport.destY(100));
        assertEquals(-2.5D, teleport.destZ(100));
    }

    @Test
    void relativeDestinationIsAddedToTheBarrierBlock() {
        BarrierTeleport teleport = BarrierTeleport.relative("minecraft:overworld", 2, 1, -4, null, null);
        assertEquals(42.5D, teleport.destX(40));
        assertEquals(71D, teleport.destY(70));
        assertEquals(-0.5D, teleport.destZ(3));
    }

    @Test
    void facingIsOptional() {
        assertNull(BarrierTeleport.absolute("minecraft:overworld", 0, 0, 0, null, null).yaw());
        assertEquals(180F, BarrierTeleport.absolute("minecraft:overworld", 0, 0, 0, 180F, 30F).yaw());
    }
}
