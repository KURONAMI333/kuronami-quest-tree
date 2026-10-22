package com.kuronami.questtree.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The FTB Quests file settings the importer keeps in {@code settings.json} (2026-10-22, p4-readers). */
class FileSettingsTest {

    private static FileSettings of(String json) {
        return FileSettings.of(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void readsTheSerializedFileAndItsDefaults() {
        FileSettings f = of("""
                {"show_lock_icons":false,"lock_message":"Locked!","hide_excluded_quests":true,
                 "disable_gui":true,"grid_scale":1.0,"pause_game":true,"detection_delay":5,
                 "default_reward_team":true,"default_consume_items":true,
                 "default_autoclaim_rewards":"enabled","default_quest_shape":"circle",
                 "default_quest_disable_jei":true,"preset":"goal",
                 "presets":{"goal":{"shape":"hexagon","size":2.0}},
                 "suppress_all_autoclaiming":true}
                """);
        assertFalse(f.lockIcons);
        assertEquals("Locked!", f.lockMessage);
        assertTrue(f.hideExcludedQuests);
        assertTrue(f.disableGui);
        assertEquals(1.0, f.gridScale);
        assertTrue(f.pauseGame);
        assertEquals(5, f.detectionDelay);
        assertTrue(f.rewardTeam);
        assertTrue(f.consumeItems);
        // the all-off switch wins over the file's own "enabled"
        assertFalse(f.auto(JsonParser.parseString("{\"type\":\"item\"}").getAsJsonObject()));
        assertEquals("enabled", f.autoClaim);
        assertEquals("circle", f.questShape);
        assertTrue(f.questDisableJei);
        assertEquals("goal", f.defaultPreset);
        assertEquals(new FileSettings.Preset("hexagon", 2.0), f.preset("goal"));
        assertTrue(f.suppressAllAutoclaiming);
        // the all-off switch beats both the file default and a reward that says "enabled"
        assertFalse(f.auto(JsonParser.parseString("{\"auto\":\"enabled\"}").getAsJsonObject()));
    }

    @Test
    void readsTheEditorsFriendlyDefaultsAndKeepsFtbsDefaults() {
        FileSettings f = of("""
                {"defaults":{"team":true,"consume_items":true,"auto":"disabled",
                 "shape":"diamond","disable_recipe_viewer":true,"preset":"normal"},
                 "presets":{"normal":{"shape":"square","size":1.0}}}
                """);
        assertTrue(f.rewardTeam);
        assertTrue(f.consumeItems);
        assertEquals("disabled", f.autoClaim);
        assertEquals(Boolean.FALSE, f.autoClaimDefault());
        assertEquals("diamond", f.questShape);
        assertTrue(f.hidesFromRecipeViewers());
        assertEquals("normal", f.defaultPreset);
        // a blank file is FTB Quests' own default, not a zero-size grid
        FileSettings empty = FileSettings.EMPTY;
        assertTrue(empty.lockIcons);
        assertEquals(0.5, empty.gridScale);
        assertEquals(20, empty.detectionDelay);
        assertFalse(empty.hidesFromRecipeViewers());
        assertNull(empty.autoClaimDefault());
        assertNull(empty.preset(""));
    }

    @Test
    void rewardTeamAndAutoFollowTheRewardThenTheFile() {
        FileSettings f = of("{\"default_reward_team\":true,\"default_autoclaim_rewards\":\"enabled\"}");
        assertTrue(f.team(new JsonObject()));
        assertFalse(f.team(JsonParser.parseString("{\"team\":false}").getAsJsonObject()));
        assertTrue(f.auto(new JsonObject()));
        assertFalse(f.auto(JsonParser.parseString("{\"auto\":false}").getAsJsonObject()));
        assertFalse(f.auto(JsonParser.parseString("{\"auto\":\"disabled\"}").getAsJsonObject()));
    }
}
