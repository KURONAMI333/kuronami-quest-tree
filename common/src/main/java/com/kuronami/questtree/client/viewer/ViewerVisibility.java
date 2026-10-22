package com.kuronami.questtree.client.viewer;

import com.kuronami.questtree.client.viewer.QuestViewerIndex.Quest;
import com.kuronami.questtree.client.viewer.QuestViewerIndex.Visibility;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers which quest keys were visible when the index was last built. A progress packet arrives often; most of
 * them change nothing the viewer lists, so {@link #changed} lets the caller skip rebuilding the whole index for a
 * visible set that is already on screen. Pure, so the rule can be tested without a client.
 */
public final class ViewerVisibility {

    private List<String> keys = List.of();
    private int generation = -1;

    /** Whether the visible set, or the rebuilt bundle data (a new generation), differs from the last apply. */
    public boolean changed(int generation, List<Quest> quests, Visibility visibility) {
        return generation != this.generation || !keysOf(quests, visibility).equals(keys);
    }

    /** Records that the index for this generation and visible set is now the one on screen. */
    public void accept(int generation, List<Quest> quests, Visibility visibility) {
        this.keys = keysOf(quests, visibility);
        this.generation = generation;
    }

    /** Forgets the remembered set, e.g. on logout. */
    public void reset() {
        keys = List.of();
        generation = -1;
    }

    /** The keys of the quests {@code visibility} allows, in bundle order. */
    public static List<String> keysOf(List<Quest> quests, Visibility visibility) {
        List<String> out = new ArrayList<>();
        if (quests == null) {
            return out;
        }
        for (Quest quest : quests) {
            if (quest != null && (visibility == null || visibility.canSee(quest))) {
                out.add(quest.key());
            }
        }
        return out;
    }
}
