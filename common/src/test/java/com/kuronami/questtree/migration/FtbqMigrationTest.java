package com.kuronami.questtree.migration;

import com.google.gson.JsonObject;
import com.kuronami.questtree.data.ProgressModel;
import com.kuronami.questtree.importer.FtbQuestsImporter;
import com.kuronami.questtree.importer.ImportOutput;
import com.kuronami.questtree.importer.Snbt;
import com.kuronami.questtree.progress.PlayerProgress;
import com.kuronami.questtree.progress.TeamProgress;
import com.kuronami.questtree.progress.TeamStoreData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTB Quests progress migration on a small hand-written book that goes through the real importer, so the node keys
 * and the task and reward positions are the ones the import produces.
 */
class FtbqMigrationTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PARTY = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /**
     * Quest 1 (hex id): checkmark + item task, xp + team item reward. Quest 2 (id with the top bit set): an unsupported
     * task first, so the item task is pack index 0. Quest 3 (decimal id 4660 = 0x1234): one task, two rewards.
     */
    private static final String BOOK = """
            {
              id: "0000000000000AAA"
              filename: "main"
              quests: [
                {
                  id: "0000000000000001"
                  tasks: [
                    { id: "0000000000000011", type: "checkmark" }
                    { id: "0000000000000012", type: "item", item: "minecraft:stone", count: 5L }
                  ]
                  rewards: [
                    { id: "0000000000000021", type: "xp", xp: 10 }
                    { id: "0000000000000022", type: "item", item: "minecraft:apple", team_reward: true }
                  ]
                }
                {
                  id: "FEDCBA9876543210"
                  tasks: [
                    { id: "0000000000000031", type: "ftbquests:no_such_task" }
                    { id: "0000000000000032", type: "item", item: "minecraft:dirt" }
                  ]
                  rewards: [
                    { id: "0000000000000041", type: "no_such_reward" }
                    { id: "0000000000000042", type: "xp", xp: 1 }
                  ]
                }
                {
                  id: 4660
                  tasks: [ { id: "0000000000000051", type: "checkmark" } ]
                  rewards: [
                    { id: "0000000000000061", type: "xp", xp: 2 }
                    { id: "0000000000000062", type: "xp", xp: 3 }
                  ]
                }
              ]
            }
            """;

    private ProgressModel model;
    private FtbqBook book;
    private TeamStoreData store;
    private MigrationReport report;
    /** FTB Teams style: the KQT team of a member is the party, anyone else is alone. */
    private final Function<UUID, UUID> inParty = p -> p.equals(ALICE) || p.equals(BOB) ? PARTY : p;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        Path chapters = tmp.resolve("chapters");
        Files.createDirectories(chapters);
        Files.writeString(chapters.resolve("main.snbt"), BOOK, StandardCharsets.UTF_8);
        ImportOutput out = new ImportOutput("t");
        FtbQuestsImporter.convert(tmp, out);
        JsonObject bundle = new JsonObject();
        JsonObject all = new JsonObject();
        out.chapters.forEach((k, v) -> all.add("t:" + k, v));
        bundle.add("chapters", all);
        model = ProgressModel.of(bundle);
        book = FtbqBook.read(tmp);
        store = new TeamStoreData();
        report = new MigrationReport();
    }

    private static final String Q1 = "t:main/0000000000000001";
    private static final String Q2 = "t:main/fedcba9876543210";
    private static final String Q3 = "t:main/4660";

    private FtbqTeamData team(String snbt) {
        return FtbqTeamData.parse(Snbt.parse(snbt).getAsJsonObject());
    }

    private void run(Function<UUID, UUID> teamOf, FtbTeamsMembers members, FtbqTeamData... teams) {
        FtbqMigration.run(new FtbqMigration.Input("t", model, book, List.of(teams), members, teamOf), store, report);
    }

    @Test
    void aBrokenChapterFileIsReportedAndTheRestStillLoads(@TempDir Path tmp) throws Exception {
        Path chapters = Files.createDirectories(tmp.resolve("chapters"));
        Files.writeString(chapters.resolve("main.snbt"), BOOK, StandardCharsets.UTF_8);
        Files.writeString(chapters.resolve("broken.snbt"), "{ id: 'x' quests: [ {", StandardCharsets.UTF_8);
        MigrationReport r = new MigrationReport();

        FtbqBook book = FtbqBook.read(tmp, r);

        assertEquals(3, book.questCount());
        assertEquals(List.of("unreadable_file broken.snbt"), r.unresolvedList());
    }

    @Test
    void nonNumericTeamValuesAreSkippedByKeyAndTheFileStillParses() {
        FtbqTeamData t = team("""
                {
                  uuid: "0000000000000000000000000000000a"
                  completion_count: {
                    "0000000000000001": "not a number"
                    "0000000000000011": true
                    "0000000000001234": 2
                  }
                  repeatable: {
                    "0000000000000001": { nested: 1 }
                    "0000000000000002": "nope"
                    "0000000000000003": 5000L
                  }
                }
                """);

        assertEquals(2, t.completionCount.get(0x1234L));
        assertFalse(t.completionCount.containsKey(1L));
        assertEquals(5000L, t.repeatable.get(3L));
        assertTrue(t.badKeys.contains("completion_count.0000000000000001"));
        assertTrue(t.badKeys.contains("completion_count.0000000000000011"));
        assertTrue(t.badKeys.contains("repeatable.0000000000000001"));
        assertTrue(t.badKeys.contains("repeatable.0000000000000002"));
    }

    @Test
    void importKeysAreWhatTheMigrationUses() {
        assertNotNull(model.nodes.get(Q1));
        assertNotNull(model.nodes.get(Q2));
        assertNotNull(model.nodes.get(Q3));
        // the unsupported task and reward are dropped by the importer: one task and one reward are left on quest 2
        assertEquals(1, model.nodes.get(Q2).tasks.size());
        assertEquals(1, model.nodes.get(Q2).rewards.size());
    }

    @Test
    void completedMixesQuestAndTaskIdsAndHexMeetsDecimal() {
        FtbqTeamData t = team("""
                {
                  uuid: "0000000000000000000000000000000a"
                  completed: {
                    "0000000000000001": 100L
                    "0000000000000011": 100L
                    "0000000000000AAA": 100L
                    FEDCBA9876543210: 100L
                    "0000000000001234": 100L
                    "00000000DEADBEEF": 100L
                  }
                  started: { "0000000000000001": 90L, "0000000000000011": 90L }
                }
                """);
        run(p -> p, null, t);

        PlayerProgress shared = store.team(ALICE).shared;
        assertEquals(java.util.Set.of(Q1, Q2, Q3), shared.completed);
        assertEquals(3, report.get("quests_found"));
        assertEquals(3, report.get("quests_added"));
        // the task id and the chapter id are expected in the map and are not reported as unknown
        assertEquals(2, report.get("completed_tasks_and_chapters_skipped"));
        assertEquals(List.of("completed 00000000DEADBEEF team " + ALICE), report.unresolvedList());
        assertEquals(1, report.get("unresolved"));
        assertEquals(2, report.get("started_not_carried"));
    }

    @Test
    void taskProgressUsesThePackIndexNotTheBookPosition() {
        FtbqTeamData t = team("""
                {
                  uuid: "0000000000000000000000000000000a"
                  task_progress: {
                    "0000000000000012": 3L
                    "0000000000000011": 1L
                    "0000000000000031": 7L
                    "0000000000000032": 1L
                    "0000000000000099": 4L
                  }
                }
                """);
        run(p -> p, null, t);

        Map<String, Long> tasks = store.team(ALICE).shared.tasks;
        assertEquals(3L, tasks.get(Q1 + "#1"));
        assertEquals(1L, tasks.get(Q1 + "#0"));
        // the unsupported task has no pack counterpart and is dropped; the item task is index 0
        assertEquals(1L, tasks.get(Q2 + "#0"));
        assertEquals(3, tasks.size());
        assertEquals(1, report.get("tasks_not_converted"));
        assertEquals(List.of("task_progress 0000000000000099 team " + ALICE), report.unresolvedList());
    }

    @Test
    void claimsAreKeptPerPlayerAndTeamRewardsSettleOnce() {
        FtbqTeamData t = team("""
                {
                  uuid: "000000000000000000000000000000ff"
                  completed: { "0000000000000001": 100L, "0000000000001234": 100L }
                  claimed_rewards: {
                    "0000000000000000000000000000000a:0000000000000021": 200L
                    "00000000000000000000000000000000:0000000000000022": 200L
                    "0000000000000000000000000000000b:0000000000000061": 200L
                    "0000000000000000000000000000000a:0000000000000061": 200L
                    "0000000000000000000000000000000a:0000000000000062": 200L
                    "0000000000000000000000000000000a:0000000000000041": 200L
                  }
                }
                """);
        FtbTeamsMembers members = new FtbTeamsMembers();
        members.add(Snbt.parse("""
                {
                  id: "00000000-0000-0000-0000-0000000000ff"
                  type: "party"
                  owner: "00000000-0000-0000-0000-00000000000a"
                  ranks: {
                    "00000000-0000-0000-0000-00000000000a": "owner"
                    "00000000-0000-0000-0000-00000000000b": "member"
                    "00000000-0000-0000-0000-0000000000cc": "invited"
                  }
                }
                """).getAsJsonObject());
        run(inParty, members, t);

        TeamProgress party = store.team(PARTY);
        assertEquals(java.util.Set.of(Q1, Q3), party.shared.completed);
        // the NIL claim is the team reward, kept once for the whole party
        assertEquals(java.util.Set.of(Q1 + "#1"), party.teamClaimedRewards);
        assertEquals(java.util.Set.of(Q1 + "#0", Q3 + "#0", Q3 + "#1"), party.part(ALICE).claimedRewards);
        assertEquals(java.util.Set.of(Q3 + "#0"), party.part(BOB).claimedRewards);
        // Alice has both rewards of quest 1 (one of them through the team) and both of quest 3; Bob only one of quest 3
        assertEquals(java.util.Set.of(Q1, Q3), party.part(ALICE).claimed);
        assertTrue(party.part(BOB).claimed.isEmpty());
        // Bob's view reads the team reward as claimed too
        assertTrue(party.view(BOB).rewardClaimed(Q1 + "#1"));
        assertFalse(party.view(BOB).rewardClaimed(Q1 + "#0"));
        // a reward the importer dropped is counted, not reported as unknown
        assertEquals(1, report.get("rewards_not_converted"));
        assertEquals(0, report.get("unresolved"));
        assertEquals(1, report.get("team_rewards_found"));
        assertEquals(4, report.get("rewards_found"));
        // the invited player is not a member
        assertNull(store.teams.get(UUID.fromString("00000000-0000-0000-0000-0000000000cc")));
        assertEquals(2, report.get("players"));
    }

    @Test
    void teamRewardCompletesTheNodeForAMemberWhoClaimedTheRest() {
        FtbqTeamData t = team("""
                {
                  uuid: "000000000000000000000000000000ff"
                  claimed_rewards: {
                    "00000000000000000000000000000000:0000000000000022": 200L
                    "0000000000000000000000000000000b:0000000000000021": 200L
                  }
                }
                """);
        FtbTeamsMembers members = new FtbTeamsMembers();
        members.add(Snbt.parse("""
                { id: "00000000-0000-0000-0000-0000000000ff", type: "party",
                  ranks: { "00000000-0000-0000-0000-00000000000a": "member", "00000000-0000-0000-0000-00000000000b": "member" } }
                """).getAsJsonObject());
        run(inParty, members, t);

        TeamProgress party = store.team(PARTY);
        assertEquals(java.util.Set.of(Q1), party.part(BOB).claimed);
        // Alice claimed nothing herself: only the team reward is there, so the node is not claimed for her
        assertTrue(party.part(ALICE).claimed.isEmpty());
    }

    @Test
    void repeatCountsAndCooldownsComeAcrossAndTakeTheLarger() {
        TeamProgress existing = store.team(ALICE);
        existing.shared.repeats.put(Q1, 9);
        FtbqTeamData t = team("""
                {
                  uuid: "0000000000000000000000000000000a"
                  completion_count: { "0000000000000001": 3, "0000000000001234": 2 }
                  repeatable: { "0000000000000001": 5000L }
                }
                """);
        run(p -> p, null, t);

        assertEquals(9, existing.shared.repeats.get(Q1));
        assertEquals(2, existing.shared.repeats.get(Q3));
        assertEquals(5000L, existing.shared.cooldown.get(Q1));
    }

    @Test
    void mergeOnlyKeepsExistingProgressAndASecondRunChangesNothing() {
        TeamProgress existing = store.team(ALICE);
        existing.shared.completed.add("t:other/node");
        existing.shared.tasks.put(Q1 + "#1", 40L);
        existing.part(ALICE).claimedRewards.add("t:other/node#0");
        existing.shared.xp.put("t:skill_x", 12.5);
        FtbqTeamData t = team("""
                {
                  uuid: "0000000000000000000000000000000a"
                  completed: { "0000000000000001": 100L }
                  task_progress: { "0000000000000012": 3L, "0000000000000011": 1L }
                  claimed_rewards: { "0000000000000000000000000000000a:0000000000000021": 200L }
                }
                """);
        run(p -> p, null, t);

        PlayerProgress shared = existing.shared;
        assertTrue(shared.completed.contains("t:other/node"));
        assertTrue(shared.completed.contains(Q1));
        assertEquals(40L, shared.tasks.get(Q1 + "#1"));
        assertEquals(1L, shared.tasks.get(Q1 + "#0"));
        assertEquals(12.5, shared.xp.get("t:skill_x"));
        assertTrue(existing.part(ALICE).claimedRewards.contains("t:other/node#0"));
        assertTrue(existing.part(ALICE).claimedRewards.contains(Q1 + "#0"));
        // quest 1 has a second reward that was not claimed
        assertTrue(existing.part(ALICE).claimed.isEmpty());

        String after = store.toJson().toString();
        MigrationReport second = new MigrationReport();
        FtbqMigration.run(new FtbqMigration.Input("t", model, book, List.of(t), null, p -> p), store, second);
        assertEquals(after, store.toJson().toString());
        assertEquals(0, second.get("quests_added"));
        assertEquals(0, second.get("tasks_added"));
        assertEquals(0, second.get("rewards_added"));
        assertEquals(2, second.get("tasks_found"));
    }

    @Test
    void aTeamWithoutFtbTeamsFilesIsOnePlayerAndKqtPartiesDecideTheTarget() {
        FtbqTeamData t = team("""
                {
                  uuid: "0000000000000000000000000000000a"
                  completed: { "0000000000000001": 100L }
                }
                """);
        // Alice is in a KQT party now: her FTBQ progress lands in the party's record
        run(inParty, null, t);
        assertTrue(store.team(PARTY).shared.completed.contains(Q1));
        assertFalse(store.teams.containsKey(ALICE));
    }

    @Test
    void idsThatNeitherBookNorPackKnowAreListedNotGuessed() {
        // a book that is newer than the import: quest 5 exists in the book file but not in the pack
        String extra = BOOK.replace("quests: [", """
                quests: [
                    { id: "0000000000000005", tasks: [ { id: "0000000000000071", type: "checkmark" } ] }
                """);
        // the pack is the earlier import; only the book index is the newer one
        FtbqBook newer = FtbqBook.of(Map.of("main", Snbt.parse(extra).getAsJsonObject()));
        StringBuilder done = new StringBuilder("{ uuid: \"0000000000000000000000000000000a\" completed: {");
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String id = String.format("%016X", 0x7000000000000000L + i);
            done.append('"').append(id).append("\": 1L ");
            bad.add("completed " + id + " team " + ALICE);
        }
        done.append(" \"0000000000000005\": 1L } }");
        FtbqMigration.run(new FtbqMigration.Input("t", model, newer, List.of(team(done.toString())), null, p -> p),
                store, report);

        assertEquals(31, report.get("unresolved"));
        assertEquals(31, report.unresolvedList().size());
        assertTrue(report.unresolvedList().contains("node_missing t:main/0000000000000005 (0000000000000005)"));
        assertTrue(report.unresolvedList().containsAll(bad));
        assertTrue(store.team(ALICE).shared.completed.isEmpty());
        String text = report.toText("FTB Quests", false, "now");
        assertTrue(text.contains("unresolved (31)"));
    }

    @Test
    void aTaskWhoseTextNamesAnotherIdMeansThePackWasEditedAndIsNotGuessed() {
        // swap the two task entries of quest 1 in the pack: the same indexes now hold the other task
        com.google.gson.JsonArray tasks = model.nodes.get(Q1).json.getAsJsonArray("tasks");
        com.google.gson.JsonElement first = tasks.get(0);
        tasks.set(0, tasks.get(1));
        tasks.set(1, first);
        ProgressModel edited = ProgressModel.of(wrap(model));
        FtbqTeamData t = team("""
                { uuid: "0000000000000000000000000000000a" task_progress: { "0000000000000012": 3L } }
                """);
        FtbqMigration.run(new FtbqMigration.Input("t", edited, book, List.of(t), null, p -> p), store, report);

        assertTrue(store.team(ALICE).shared.tasks.isEmpty());
        assertEquals(1, report.unresolvedByKind().get("task_moved"));
    }

    private static JsonObject wrap(ProgressModel m) {
        JsonObject bundle = new JsonObject();
        JsonObject all = new JsonObject();
        m.chapters.forEach((id, ch) -> {
            JsonObject c = new JsonObject();
            JsonObject nodes = new JsonObject();
            ch.nodes.forEach((nid, n) -> nodes.add(nid, n.json));
            c.add("nodes", nodes);
            all.add(id, c);
        });
        bundle.add("chapters", all);
        return bundle;
    }

    @Test
    void ftbTeamsFilesGivePlayersAndParties() {
        FtbTeamsMembers m = new FtbTeamsMembers();
        m.add(Snbt.parse("{ id: \"00000000-0000-0000-0000-00000000000a\", type: \"player\" }").getAsJsonObject());
        m.add(Snbt.parse("""
                { id: "00000000-0000-0000-0000-0000000000ff", type: "party", owner: "00000000-0000-0000-0000-00000000000a",
                  ranks: { "00000000-0000-0000-0000-00000000000b": "officer", "00000000-0000-0000-0000-0000000000dd": "ally" } }
                """).getAsJsonObject());
        assertEquals(java.util.Set.of(ALICE), m.members(ALICE));
        assertEquals(java.util.Set.of(ALICE, BOB), m.members(PARTY));
        assertNull(m.members(UUID.fromString("00000000-0000-0000-0000-0000000000ee")));
    }

    @Test
    void theBookItselfIsObjectOneAndIsNotReportedAsUnknown() {
        FtbqBook other = FtbqBook.of(Map.of("x", Snbt.parse("{ id: \"0000000000000AAA\" filename: \"x\" quests: [] }").getAsJsonObject()));
        assertTrue(other.isChapter(1L));
        FtbqMigration.run(new FtbqMigration.Input("t", model, other,
                List.of(team("{ uuid: \"0000000000000000000000000000000a\" completed: { \"0000000000000001\": 5L } }")),
                null, p -> p), store, report);
        assertEquals(0, report.get("unresolved"));
        assertEquals(1, report.get("completed_tasks_and_chapters_skipped"));
    }

    @Test
    void appliedMigrationsSurviveSaveAndLoadAndTheHashFollowsTheContent(@TempDir Path tmp) throws Exception {
        store.migrations.put("ftbquests:t", "abc@20261020-120000");
        TeamStoreData back = TeamStoreData.fromJson(store.toJson());
        assertEquals("abc@20261020-120000", back.migrations.get("ftbquests:t"));
        assertEquals(store.migrations, back.migrations);

        Path f = tmp.resolve("a-b.snbt");
        Files.writeString(f, "{ uuid: \"x\" }");
        String one = MigrationHash.of(List.of(f));
        assertEquals(one, MigrationHash.of(List.of(f)));
        Files.writeString(f, "{ uuid: \"y\" }");
        assertFalse(one.equals(MigrationHash.of(List.of(f))));
    }

    @Test
    void idsAreUnsignedAndAcceptDecimalNumbers() {
        assertEquals(0xFEDCBA9876543210L, FtbqIds.parse("FEDCBA9876543210").getAsLong());
        assertEquals(0x1234L, FtbqIds.parse("#1234").getAsLong());
        assertEquals(4660L, FtbqIds.parse(Snbt.parse("4660")).getAsLong());
        assertTrue(FtbqIds.parse("not an id").isEmpty());
        assertTrue(FtbqIds.parse("").isEmpty());
        assertEquals("0000000000001234", FtbqIds.hex(0x1234));
    }
}
