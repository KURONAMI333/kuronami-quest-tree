package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootCrateRulesTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void legendaryDefaultsApplyWhenFieldsAreMissing() {
        JsonObject table = json("""
                {"loot_crate": {"string_id": "legendary"}}
                """);
        LootCrateRules.Crate crate = LootCrateRules.crate("mod:table", table).orElseThrow();
        assertEquals("legendary", crate.stringId());
        assertEquals(16761159, crate.color());
        assertTrue(crate.glow());
        assertEquals(1, crate.passive());
        assertEquals(1, crate.monster());
        assertEquals(190, crate.boss());
    }

    @Test
    void writtenFieldsBeatTheDefaults() {
        JsonObject table = json("""
                {"loot_crate": {"string_id": "rare", "color": 1, "glow": true,
                  "drops": {"passive": 7, "monster": 8, "boss": 9}}}
                """);
        LootCrateRules.Crate crate = LootCrateRules.crate("mod:table", table).orElseThrow();
        assertEquals(1, crate.color());
        assertTrue(crate.glow());
        assertEquals(7, crate.weight(false, false));
        assertEquals(8, crate.weight(false, true));
        assertEquals(9, crate.weight(true, false));
    }

    @Test
    void aHexColourWrittenAsTextIsRead() {
        LootCrateRules.Crate crate = LootCrateRules.crate("mod:table",
                json("{\"loot_crate\": {\"color\": \"#12ab34\"}}")).orElseThrow();
        assertEquals(0x12AB34, crate.color());
        LootCrateRules.Crate prefixed = LootCrateRules.crate("mod:table",
                json("{\"loot_crate\": {\"color\": \"0xABCDEF\"}}")).orElseThrow();
        assertEquals(0xABCDEF, prefixed.color());
    }

    @Test
    void aTableWithoutACrateHasNone() {
        assertTrue(LootCrateRules.crate("mod:table", json("{}")).isEmpty());
        assertTrue(LootCrateRules.crate("mod:table", json("{\"loot_crate\": null}")).isEmpty());
    }

    @Test
    void aMissingStringIdFallsBackToTheTableId() {
        LootCrateRules.Crate crate = LootCrateRules.crate("mod:my:table one",
                json("{\"loot_crate\": {}}")).orElseThrow();
        assertEquals("mod_my_table_one", crate.stringId());
    }

    @Test
    void settingsReadTheFTBQNamesAndTheNewShape() {
        LootCrateRules.Settings legacy = LootCrateRules.settings(json("""
                {"drop_loot_crates": true, "loot_crate_no_drop": {"passive": 1, "monster": 2, "boss": 3}}
                """));
        assertTrue(legacy.enabled());
        assertEquals(1, legacy.noDropPassive());
        assertEquals(2, legacy.noDropMonster());
        assertEquals(3, legacy.noDropBoss());

        LootCrateRules.Settings current = LootCrateRules.settings(json("""
                {"loot_crates": {"enabled": true, "no_drop": {"passive": 4}}}
                """));
        assertTrue(current.enabled());
        assertEquals(4, current.noDropPassive());
        assertEquals(600, current.noDropMonster());
        assertEquals(0, current.noDropBoss());
    }

    @Test
    void nothingIsEnabledByDefault() {
        LootCrateRules.Settings settings = LootCrateRules.settings(new JsonObject());
        assertFalse(settings.enabled());
        assertEquals(4000, settings.noDropPassive());
        assertEquals(600, settings.noDropMonster());
        assertEquals(0, settings.noDropBoss());
    }

    @Test
    void chooseRespectsTheNoDropWeight() {
        Map<String, LootCrateRules.Crate> crates = new LinkedHashMap<>();
        crates.put("mod:a", new LootCrateRules.Crate("mod:a", "common", "", 0, false, 1, 0, 0));
        LootCrateRules.Settings settings = new LootCrateRules.Settings(true, 0, 0, 0);
        assertEquals("mod:a", LootCrateRules.choose(crates, settings, false, false, () -> 0.0).orElseThrow());
        assertEquals("mod:a", LootCrateRules.choose(crates, settings, false, false, () -> 0.5).orElseThrow());

        LootCrateRules.Settings none = new LootCrateRules.Settings(true, 1, 0, 0);
        assertTrue(LootCrateRules.choose(crates, none, false, false, () -> 0.2).isEmpty());
        assertEquals("mod:a", LootCrateRules.choose(crates, none, false, false, () -> 0.99).orElseThrow());
    }

    @Test
    void chooseUsesTheEntityClassWeight() {
        Map<String, LootCrateRules.Crate> crates = new LinkedHashMap<>();
        crates.put("mod:boss", new LootCrateRules.Crate("mod:boss", "boss", "", 0, false, 0, 0, 10));
        LootCrateRules.Settings settings = new LootCrateRules.Settings(true, 0, 0, 0);
        assertTrue(LootCrateRules.choose(crates, settings, false, false, () -> 0.5).isEmpty());
        assertEquals("mod:boss", LootCrateRules.choose(crates, settings, true, false, () -> 0.5).orElseThrow());
    }

    @Test
    void zeroWeightsChooseNothing() {
        Map<String, LootCrateRules.Crate> crates = new LinkedHashMap<>();
        crates.put("mod:a", new LootCrateRules.Crate("mod:a", "a", "", 0, false, 0, 0, 0));
        LootCrateRules.Settings settings = new LootCrateRules.Settings(true, 0, 0, 0);
        assertTrue(LootCrateRules.choose(crates, settings, false, false, () -> 0.0).isEmpty());
        assertEquals(List.of(), List.copyOf(crates.keySet()).subList(1, 1));
    }
}
