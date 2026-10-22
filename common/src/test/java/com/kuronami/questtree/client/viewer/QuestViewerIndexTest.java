package com.kuronami.questtree.client.viewer;

import com.kuronami.questtree.client.viewer.QuestViewerIndex.Hit;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Index;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Kind;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Need;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Query;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Quest;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Spec;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestViewerIndexTest {

    @Test
    void usesMatchAnItemOrOneOfItsTags() {
        Quest sticks = quest("pack:sticks", "Sticks", need(Kind.TASK, Spec.item("minecraft:stick"), 4));
        Quest logs = quest("pack:logs", "Logs", need(Kind.TASK, Spec.tag("minecraft:logs"), 8));
        Index index = QuestViewerIndex.build(List.of(sticks, logs), quest -> true);

        assertEquals(List.of("pack:sticks"), keys(index.uses(query("minecraft:stick"))));
        assertEquals(List.of("pack:logs"), keys(index.uses(query("minecraft:oak_log", "minecraft:logs"))));
        assertTrue(index.uses(query("minecraft:stone")).isEmpty());
        assertTrue(index.rewards(query("minecraft:stick")).isEmpty());
    }

    @Test
    void rewardsAreKeptApartFromTasks() {
        Quest quest = quest("pack:q", "Quest",
                need(Kind.TASK, Spec.item("minecraft:iron_ingot"), 3),
                need(Kind.REWARD, Spec.item("minecraft:diamond"), 1));
        Index index = QuestViewerIndex.build(List.of(quest), quest1 -> true);

        assertEquals(List.of(Kind.TASK), index.uses(query("minecraft:iron_ingot")).stream()
                .map(hit -> hit.need().kind()).toList());
        assertEquals(List.of(Kind.REWARD), index.rewards(query("minecraft:diamond")).stream()
                .map(hit -> hit.need().kind()).toList());
        assertTrue(index.uses(query("minecraft:diamond")).isEmpty());
    }

    @Test
    void hiddenQuestsAreLeftOut() {
        Quest shown = quest("pack:shown", "Shown", need(Kind.TASK, Spec.item("minecraft:apple"), 1));
        Quest secret = quest("pack:secret", "Secret", need(Kind.TASK, Spec.item("minecraft:apple"), 1));
        Index index = QuestViewerIndex.build(List.of(shown, secret), quest -> !quest.key().equals("pack:secret"));

        assertEquals(List.of("pack:shown"), keys(index.uses(query("minecraft:apple"))));
        assertEquals(1, index.hits().size());
        assertEquals(List.of("pack:shown"), index.quests().stream().map(Quest::key).toList());
    }

    @Test
    void aMissingVisibilityCheckKeepsEveryQuest() {
        Quest quest = quest("pack:q", "Quest", need(Kind.TASK, Spec.item("minecraft:apple"), 1));
        Index index = QuestViewerIndex.build(List.of(quest), null);

        assertEquals(List.of("pack:q"), keys(index.uses(query("minecraft:apple"))));
    }

    @Test
    void signaturesIgnorePlayerStateButFollowListedEntries() {
        Quest quest = quest("pack:q", "Quest", need(Kind.TASK, Spec.item("minecraft:coal"), 1));
        Index visible = QuestViewerIndex.build(List.of(quest), quest1 -> true);
        Index hidden = QuestViewerIndex.build(List.of(quest), quest1 -> false);
        Index again = QuestViewerIndex.build(List.of(quest), quest1 -> true);

        assertEquals(visible.signature(), again.signature());
        assertNotEquals(visible.signature(), hidden.signature());
        assertTrue(hidden.isEmpty());
        assertFalse(visible.isEmpty());
    }

    @Test
    void aFilterTaskMatchesThroughItsExpression() {
        Quest quest = quest("pack:any_log", "Any Log",
                need(Kind.TASK, Spec.filter("item_tag(minecraft:logs)"), 8));
        Index index = QuestViewerIndex.build(List.of(quest), quest1 -> true);

        assertEquals(List.of("pack:any_log"), keys(index.uses(filterQuery("minecraft:oak_log", "item_tag(minecraft:logs)"))));
        assertTrue(index.uses(filterQuery("minecraft:stone", "item(minecraft:stone)")).isEmpty());
    }

    @Test
    void aComponentSpecificTaskOnlyMatchesTheVariantItNames() {
        String itemJson = "{\"id\":\"minecraft:potion\",\"components\":{\"minecraft:potion_contents\":\"minecraft:healing\"}}";
        Quest quest = quest("pack:healing", "Healing",
                new Need(Kind.TASK, Spec.item("minecraft:potion"), 1, "Healing Potion", 0, false,
                        itemJson, "FUZZY"));
        Index index = QuestViewerIndex.build(List.of(quest), quest1 -> true);

        assertEquals(List.of("pack:healing"), keys(index.uses(componentQuery(itemJson, true))));
        assertTrue(index.uses(componentQuery(itemJson, false)).isEmpty());
    }

    @Test
    void aTaskWithoutAComponentModeIgnoresTheComponentCheck() {
        Quest quest = quest("pack:plain", "Plain", new Need(Kind.TASK, Spec.item("minecraft:potion"), 1,
                "Potion", 0, false, "{\"id\":\"minecraft:potion\"}", "NONE"));
        Index index = QuestViewerIndex.build(List.of(quest), quest1 -> true);

        assertEquals(List.of("pack:plain"), keys(index.uses(componentQuery("", false))));
    }

    @Test
    void tagEntriesAreNotCapped() {
        List<String> members = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            members.add("minecraft:item_" + i);
        }

        assertEquals(100, QuestViewerIndex.tagEntries(members).size());
        assertEquals("minecraft:item_99", QuestViewerIndex.tagEntries(members).get(99));
    }

    @Test
    void countAndLabelSurviveForTheViewerText() {
        Quest quest = quest("pack:q", "Quest",
                new Need(Kind.TASK, Spec.item("minecraft:oak_log"), 32, "Oak Log", 2, false));
        Hit hit = QuestViewerIndex.build(List.of(quest), quest1 -> true)
                .uses(query("minecraft:oak_log")).get(0);

        assertEquals(32, hit.need().count());
        assertEquals("Oak Log", hit.need().label());
        assertEquals(2, hit.need().index());
    }

    private static Quest quest(String key, String title, Need... needs) {
        return new Quest(key, key.substring(0, key.indexOf(':')),
                title, "Chapter", "minecraft:book", Arrays.asList(needs));
    }

    private static Need need(Kind kind, Spec spec, long count) {
        return new Need(kind, spec, count, spec.id(), 0, false);
    }

    private static Query query(String itemId, String... tags) {
        Set<String> tagSet = Set.of(tags);
        return new Query() {
            @Override
            public String itemId() {
                return itemId;
            }

            @Override
            public boolean inTag(String tagId) {
                return tagSet.contains(tagId);
            }
        };
    }

    private static Query componentQuery(String expectedJson, boolean passes) {
        return new Query() {
            @Override
            public String itemId() {
                return "minecraft:potion";
            }

            @Override
            public boolean inTag(String tagId) {
                return false;
            }

            @Override
            public boolean accepts(String itemJson, String matchMode) {
                return passes && itemJson.equals(expectedJson);
            }
        };
    }

    private static Query filterQuery(String itemId, String passingExpression) {
        return new Query() {
            @Override
            public String itemId() {
                return itemId;
            }

            @Override
            public boolean inTag(String tagId) {
                return false;
            }

            @Override
            public boolean inFilter(String expression) {
                return expression.equals(passingExpression);
            }
        };
    }

    private static List<String> keys(List<Hit> hits) {
        return hits.stream().map(hit -> hit.quest().key()).toList();
    }
}
