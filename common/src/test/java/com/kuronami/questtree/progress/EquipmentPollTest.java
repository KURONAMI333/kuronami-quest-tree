package com.kuronami.questtree.progress;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The equipment check runs on a spread-out interval and skips an unchanged signature (2026-10-22 review, L1). */
class EquipmentPollTest {

    private static UUID id(long low) {
        return new UUID(0L, low);
    }

    @Test
    void eachPlayerHasOneTurnPerInterval() {
        UUID player = id(0L);
        assertTrue(EquipmentPoll.due(0L, player));
        assertFalse(EquipmentPoll.due(1L, player));
        assertFalse(EquipmentPoll.due(4L, player));
        assertTrue(EquipmentPoll.due(EquipmentPoll.INTERVAL, player));
    }

    @Test
    void playersAreSpreadOverTheInterval() {
        Set<Integer> offsets = new HashSet<>();
        for (long low = 0; low < 10; low++) {
            UUID player = id(low);
            for (int tick = 0; tick < EquipmentPoll.INTERVAL; tick++) {
                if (EquipmentPoll.due(tick, player)) {
                    offsets.add(tick);
                    break;
                }
            }
        }
        assertEquals(EquipmentPoll.INTERVAL, offsets.size(), "every tick in the interval belongs to some player");
    }

    @Test
    void theSameEquipmentRulesAndRevisionGiveTheSameSignature() {
        List<String> worn = List.of("minecraft:diamond_helmet", "", "", "", "minecraft:shield");
        long signature = EquipmentPoll.signature(worn, 7L, 3L);
        assertEquals(signature, EquipmentPoll.signature(List.copyOf(worn), 7L, 3L));
        assertNotEquals(signature, EquipmentPoll.signature(worn, 8L, 3L), "a reloaded rule set is checked again");
        assertNotEquals(signature, EquipmentPoll.signature(worn, 7L, 4L), "changed progress is checked again");
        assertNotEquals(signature, EquipmentPoll.signature(
                List.of("minecraft:diamond_helmet", "", "", "", "minecraft:bow"), 7L, 3L));
    }
}
