package com.kuronami.questtree.config;

import com.google.gson.JsonObject;
import com.kuronami.questtree.progress.RewardPermission;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One broken value in the server config must not take the other keys down with it (2026-10-24 review). */
class ServerConfigTest {

    @Test
    void aBrokenKeyFallsBackToItsDefaultWithoutResettingTheOthers() {
        JsonObject o = new JsonObject();
        o.addProperty("give_book_on_first_join", false);
        o.addProperty("drop_book_on_death", true);
        o.add("reward_command_permission_level", new JsonObject());
        o.addProperty("validation_report", false);
        List<String> reset = new ArrayList<>();

        ServerConfig.Values values = ServerConfig.valuesOf(o, reset);

        assertFalse(values.giveBook());
        assertTrue(values.dropBook());
        assertEquals(RewardPermission.DEFAULT, values.rewardPermission());
        assertFalse(values.validationReport());
        assertEquals(List.of("reward_command_permission_level"), reset);
    }

    @Test
    void aNonNumericPermissionFallsBackAndGoodKeysStillApply() {
        JsonObject o = new JsonObject();
        o.addProperty("reward_command_permission_level", "not a number");
        o.addProperty("validation_report", false);
        List<String> reset = new ArrayList<>();

        ServerConfig.Values values = ServerConfig.valuesOf(o, reset);

        assertEquals(RewardPermission.DEFAULT, values.rewardPermission());
        assertFalse(values.validationReport());
        assertEquals(List.of("reward_command_permission_level"), reset);
    }
}
