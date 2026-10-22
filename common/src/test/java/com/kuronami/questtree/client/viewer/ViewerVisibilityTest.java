package com.kuronami.questtree.client.viewer;

import com.kuronami.questtree.client.viewer.QuestViewerIndex.Kind;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Need;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Quest;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Spec;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerVisibilityTest {

    @Test
    void anUnchangedVisibleSetDoesNotAskForARebuild() {
        Quest quest = quest("pack:q");
        Visibility shown = quest1 -> true;
        ViewerVisibility state = new ViewerVisibility();
        state.accept(1, List.of(quest), shown);

        assertFalse(state.changed(1, List.of(quest), shown));
    }

    @Test
    void aQuestBecomingVisibleAsksForARebuild() {
        Quest quest = quest("pack:q");
        ViewerVisibility state = new ViewerVisibility();
        state.accept(1, List.of(quest), quest1 -> false);

        assertTrue(state.changed(1, List.of(quest), quest1 -> true));
    }

    @Test
    void newBundleDataAsksForARebuildEvenWhenTheSameQuestsStayVisible() {
        Quest quest = quest("pack:q");
        Visibility shown = quest1 -> true;
        ViewerVisibility state = new ViewerVisibility();
        state.accept(1, List.of(quest), shown);

        assertTrue(state.changed(2, List.of(quest), shown));
    }

    private static Quest quest(String key) {
        return new Quest(key, key.substring(0, key.indexOf(':')), "Quest", "Chapter", "minecraft:book",
                List.of(new Need(Kind.TASK, Spec.item("minecraft:stick"), 1, "Stick", 0, false)));
    }
}
