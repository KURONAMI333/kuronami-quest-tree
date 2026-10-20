package com.kuronami.questtree.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.importer.ImportOutput;
import com.kuronami.questtree.importer.PuffishImporter;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.TeamStoreData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pufferfish's Skills migration on the mining fixture imported by the real importer (curve {@code level^1.5 * 100},
 * seven skills of cost 1, no starting points).
 */
class PuffishMigrationTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PARTY = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final String CHAPTER = "t:skill_ftb_mining";
    private static final String CONFIG_CHAPTER = "t:skill_config_mining";

    private static ProgressModel model(boolean lockedByDefault) throws Exception {
        ImportOutput out = new ImportOutput("t");
        Map<String, JsonElement> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(Path.of(PuffishMigrationTest.class.getResource("/fixtures/puffish/mining").toURI()))) {
            for (Path f : s.toList()) {
                files.put(f.getFileName().toString(), JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        PuffishImporter.convert("ftb/mining", files, out);
        PuffishImporter.convert("config/mining", files, out);
        JsonObject all = new JsonObject();
        out.chapters.forEach((k, v) -> {
            if (lockedByDefault) {
                v.getAsJsonObject("points").addProperty("unlocked_by_default", false);
            }
            all.add("t:" + k, v);
        });
        JsonObject bundle = new JsonObject();
        bundle.add("chapters", all);
        return ProgressModel.of(bundle);
    }

    private static Map<UUID, JsonObject> players(String bobJson, String aliceJson) {
        Map<UUID, JsonObject> m = new LinkedHashMap<>();
        if (aliceJson != null) {
            m.put(ALICE, JsonParser.parseString(aliceJson).getAsJsonObject());
        }
        if (bobJson != null) {
            m.put(BOB, JsonParser.parseString(bobJson).getAsJsonObject());
        }
        return m;
    }

    private static MigrationReport run(ProgressModel model, TeamStoreData store, Map<UUID, JsonObject> players,
                                       java.util.function.Function<UUID, UUID> teamOf) {
        MigrationReport report = new MigrationReport();
        PuffishMigration.run(new PuffishMigration.Input(null, model, players, teamOf), store, report);
        return report;
    }

    @Test
    void skillsExperienceAndPointsAreMappedOntoTheKqtCurve() throws Exception {
        TeamStoreData store = new TeamStoreData();
        MigrationReport r = run(model(false), store, players(null, """
                {"categories": {"ftb:mining": {
                  "unlocked": 1, "experience": 200, "level": 0,
                  "unlocked_skills": ["root", "s0"],
                  "points": {"puffish_skills:starting": 0, "puffish_skills:experience": 5}}}}
                """), p -> p);

        PlayerProgress shared = store.team(ALICE).shared;
        assertEquals(1, shared.taken.get(CHAPTER + "/root"));
        assertEquals(1, shared.taken.get(CHAPTER + "/s0"));
        assertEquals(2, shared.taken.size());
        // 1 for level 0 (the curve is clamped to 1), then 100, then 282.8: 200 total is level 2 with 99 inside it
        assertEquals(2, shared.level.get(CHAPTER));
        assertEquals(99.0, shared.xp.get(CHAPTER), 1e-9);
        assertEquals(2, shared.spent.get(CHAPTER));
        // 5 points in total, 2 come from the levels, the other 3 are bonus
        assertEquals(3, shared.bonus.get(CHAPTER));
        assertEquals(2, r.get("skills_added"));
        assertEquals(0, r.get("unresolved"));
    }

    @Test
    void legacyNumberPointsAndConfigCategoriesWork() throws Exception {
        TeamStoreData store = new TeamStoreData();
        // an old file writes points as one number; a category from the config folder has no namespace in the data
        MigrationReport r = run(model(false), store, players(null, """
                {"categories": {"mining": {"unlocked": 1, "experience": 0, "unlocked_skills": ["s1"], "points": 4}}}
                """), p -> p);

        PlayerProgress shared = store.team(ALICE).shared;
        assertEquals(1, shared.taken.get(CONFIG_CHAPTER + "/s1"));
        assertEquals(4, shared.bonus.get(CONFIG_CHAPTER));
        assertEquals(0, r.get("unresolved"));
    }

    @Test
    void pointsBelowTheKqtLevelAreNotTakenAwayAndAreCounted() throws Exception {
        TeamStoreData store = new TeamStoreData();
        MigrationReport r = run(model(false), store, players(null, """
                {"categories": {"ftb:mining": {"experience": 200, "points": 1}}}
                """), p -> p);

        assertFalse(store.team(ALICE).shared.bonus.containsKey(CHAPTER));
        assertEquals(1, r.get("points_over_source"));
    }

    @Test
    void mergeOnlyKeepsAHigherLevelAndExistingBonusAndASecondRunChangesNothing() throws Exception {
        ProgressModel model = model(false);
        TeamStoreData store = new TeamStoreData();
        PlayerProgress existing = store.team(ALICE).shared;
        existing.level.put(CHAPTER, 6);
        existing.xp.put(CHAPTER, 10.0);
        existing.bonus.put(CHAPTER, 9);
        existing.taken.put(CHAPTER + "/root", 1);
        existing.spent.put(CHAPTER, 1);
        Map<UUID, JsonObject> data = players(null, """
                {"categories": {"ftb:mining": {"experience": 200, "unlocked_skills": ["root", "s2"], "points": 5}}}
                """);
        MigrationReport first = run(model, store, data, p -> p);

        assertEquals(6, existing.level.get(CHAPTER));
        assertEquals(10.0, existing.xp.get(CHAPTER));
        assertEquals(9, existing.bonus.get(CHAPTER));
        // the root was already taken and its cost already counted; only s2 adds one
        assertEquals(2, existing.spent.get(CHAPTER));
        assertEquals(1, first.get("skills_added"));

        String after = store.toJson().toString();
        MigrationReport second = run(model, store, data, p -> p);
        assertEquals(after, store.toJson().toString());
        assertEquals(0, second.get("skills_added"));
        assertEquals(0, second.get("bonus_added"));
    }

    @Test
    void teammatesFoldIntoOneTeamRecordWithTheLargerValues() throws Exception {
        TeamStoreData store = new TeamStoreData();
        run(model(false), store, players("""
                {"categories": {"ftb:mining": {"experience": 101, "unlocked_skills": ["s3"], "points": 2}}}
                """, """
                {"categories": {"ftb:mining": {"experience": 1, "unlocked_skills": ["s2"], "points": 1}}}
                """), p -> PARTY);

        PlayerProgress shared = store.team(PARTY).shared;
        assertEquals(2, shared.level.get(CHAPTER));
        assertEquals(2, shared.taken.size());
        assertEquals(2, shared.spent.get(CHAPTER));
        assertTrue(store.teams.size() == 1);
    }

    @Test
    void aLockedCategoryThePlayerUnlockedIsOpenedForTheirTeam() throws Exception {
        TeamStoreData store = new TeamStoreData();
        run(model(true), store, players(null, """
                {"categories": {"ftb:mining": {"unlocked": 1, "unlocked_skills": []},
                                "config:other": {"unlocked": 0}}}
                """), p -> p);

        assertEquals(true, store.team(ALICE).shared.chapterOpen.get(CHAPTER));
    }

    @Test
    void unknownCategoriesAndSkillsAreListedNotGuessed() throws Exception {
        TeamStoreData store = new TeamStoreData();
        MigrationReport r = run(model(false), store, players(null, """
                {"categories": {"other:magic": {"unlocked_skills": ["a"]},
                                "ftb:mining": {"unlocked_skills": ["s0", "gone_skill"]}}}
                """), p -> p);

        assertEquals(2, r.get("unresolved"));
        assertTrue(r.unresolvedList().contains("category other:magic"));
        assertTrue(r.unresolvedList().stream().anyMatch(s -> s.startsWith("skill ftb:mining gone_skill")));
        assertEquals(1, store.team(ALICE).shared.taken.size());
    }

    @Test
    void exchangeCountComesAcrossAndTheLargerValueStays() throws Exception {
        ProgressModel model = model(false);
        TeamStoreData store = new TeamStoreData();
        Map<UUID, JsonObject> data = players(null, """
                {"categories": {"ftb:mining": {"level": 3, "points": 3}}}
                """);
        MigrationReport first = run(model, store, data, p -> p);
        assertEquals(3, store.team(ALICE).shared.exchange.get(CHAPTER));
        assertEquals(1, first.get("exchange_added"));

        store.team(ALICE).shared.exchange.put(CHAPTER, 8);
        run(model, store, data, p -> p);
        assertEquals(8, store.team(ALICE).shared.exchange.get(CHAPTER));
        assertEquals(0, run(model, store, data, p -> p).get("exchange_added"));
    }

    private static ProgressModel valuesModel() {
        JsonObject bundle = JsonParser.parseString("""
                {"chapters": {"t:skill_ftb_mining": {
                  "points": {"per_level": {"type": "values", "data": {"values": [10, 20, 30]}}},
                  "nodes": {"root": {"cost": 1}}}}}
                """).getAsJsonObject();
        return ProgressModel.of(bundle);
    }

    @Test
    void valuesCurveReadsTheLastEntryForHigherLevelsAndMigratesWithoutFailing() {
        ProgressModel model = valuesModel();
        var rules = new com.kuronami.questtree.progress.ProgressRules(model);
        var chapter = model.chapters.get(CHAPTER);
        assertEquals(10, rules.experienceForLevel(chapter, 0));
        assertEquals(30, rules.experienceForLevel(chapter, 2));
        assertEquals(30, rules.experienceForLevel(chapter, 9));

        TeamStoreData store = new TeamStoreData();
        run(model, store, players(null, """
                {"categories": {"ftb:mining": {"experience": 65}}}
                """), p -> p);
        // 10 + 20 + 30 would be 60: level 3 with 5 left, and the last value repeats from here on
        assertEquals(3, store.team(ALICE).shared.level.get(CHAPTER));
        assertEquals(5.0, store.team(ALICE).shared.xp.get(CHAPTER), 1e-9);
    }

    @Test
    void levelRequirementsAreWholeNumbersLikePufferfish() throws Exception {
        ProgressModel model = model(false);
        var rules = new com.kuronami.questtree.progress.ProgressRules(model);
        // level^1.5 * 100 at level 2 is 282.84...; Pufferfish rounds each requirement
        assertEquals(283.0, rules.experienceForLevel(model.chapters.get(CHAPTER), 2));
        TeamStoreData store = new TeamStoreData();
        run(model, store, players(null, """
                {"categories": {"ftb:mining": {"experience": 384}}}
                """), p -> p);
        // 1 + 100 + 283 = 384 exactly: level 3 with nothing left (unrounded it would have been 0.16 short of it)
        assertEquals(3, store.team(ALICE).shared.level.get(CHAPTER));
        assertEquals(0.0, store.team(ALICE).shared.xp.get(CHAPTER), 1e-9);
    }

    @Test
    void pointsTotalReadsBothFormats() {
        assertEquals(7, PuffishMigration.pointsTotal(JsonParser.parseString("7")));
        assertEquals(5, PuffishMigration.pointsTotal(JsonParser.parseString("{\"a\": 2, \"b\": 3}")));
        assertEquals(0, PuffishMigration.pointsTotal(null));
    }
}
