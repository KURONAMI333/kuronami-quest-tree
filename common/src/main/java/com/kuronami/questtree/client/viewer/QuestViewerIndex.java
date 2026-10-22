package com.kuronami.questtree.client.viewer;

import java.util.ArrayList;
import java.util.List;

/**
 * The item-to-quest table shared by JEI, EMI and REI. Pure (no game classes) so the matching rules can be tested
 * without a client: a quest appears in "uses" when one of its item tasks needs the item, and in "recipes" when one of
 * its rewards gives it. Tags are matched by id, never expanded here, so a pack's tag covers every item in it.
 */
public final class QuestViewerIndex {

    private QuestViewerIndex() {
    }

    public enum Kind {
        TASK, REWARD
    }

    /** What a task or reward names: one item, or an item tag ({@code #tag}). */
    public record Spec(String id, boolean tag) {
        public Spec {
            id = id == null ? "" : id;
        }

        public static Spec item(String id) {
            return new Spec(id, false);
        }

        public static Spec tag(String id) {
            return new Spec(id, true);
        }

        public boolean matches(Query query) {
            if (id.isEmpty() || query == null) {
                return false;
            }
            return tag ? query.inTag(id) : id.equals(query.itemId());
        }
    }

    /** One item the viewer knows about. Tags are asked of the stack, so this never needs the tag registry. */
    public interface Query {
        String itemId();

        boolean inTag(String tagId);
    }

    /** One item named by a task or reward. {@code index} is the task/reward index in its quest. */
    public record Need(Kind kind, Spec spec, long count, String label, int index, boolean choice) {
    }

    /** A quest as the viewer shows it: its own icon and title, its chapter, and every item it needs or gives. */
    public record Quest(String key, String chapterId, String title, String chapterTitle, String iconItemId,
                        List<Need> needs) {
        public Quest {
            needs = List.copyOf(needs);
        }
    }

    public record Hit(Quest quest, Need need) {
    }

    @FunctionalInterface
    public interface Visibility {
        boolean canSee(Quest quest);
    }

    public static final class Index {
        private static final Index EMPTY = new Index(List.of(), List.of(), "");

        private final List<Quest> quests;
        private final List<Hit> hits;
        private final String signature;

        private Index(List<Quest> quests, List<Hit> hits, String signature) {
            this.quests = List.copyOf(quests);
            this.hits = List.copyOf(hits);
            this.signature = signature;
        }

        public static Index empty() {
            return EMPTY;
        }

        public List<Quest> quests() {
            return quests;
        }

        public List<Hit> hits() {
            return hits;
        }

        public boolean isEmpty() {
            return hits.isEmpty();
        }

        /** Everything a task of the quest needs. */
        public List<Hit> uses(Query query) {
            return find(Kind.TASK, query);
        }

        /** Everything a reward of the quest gives. */
        public List<Hit> rewards(Query query) {
            return find(Kind.REWARD, query);
        }

        public List<Hit> all(Kind kind) {
            List<Hit> out = new ArrayList<>();
            for (Hit hit : hits) {
                if (hit.need().kind() == kind) {
                    out.add(hit);
                }
            }
            return List.copyOf(out);
        }

        /**
         * Changes whenever what the viewers list changes. Player state is deliberately left out: finishing a quest
         * changes its label, not which quests need an item, so EMI does not have to reload for it.
         */
        public String signature() {
            return signature;
        }

        private List<Hit> find(Kind kind, Query query) {
            if (query == null) {
                return List.of();
            }
            List<Hit> out = new ArrayList<>();
            for (Hit hit : hits) {
                if (hit.need().kind() == kind && hit.need().spec().matches(query)) {
                    out.add(hit);
                }
            }
            return List.copyOf(out);
        }
    }

    /** Keeps only the quests {@code visibility} allows and flattens them to one hit per task/reward item. */
    public static Index build(List<Quest> quests, Visibility visibility) {
        List<Quest> visibleQuests = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        for (Quest quest : quests) {
            if (quest == null || (visibility != null && !visibility.canSee(quest))) {
                continue;
            }
            visibleQuests.add(quest);
            signature.append(quest.key()).append('\u0001')
                    .append(quest.chapterId()).append('\u0001')
                    .append(quest.title()).append('\u0001')
                    .append(quest.chapterTitle()).append('\u0001')
                    .append(quest.iconItemId()).append('\u0002');
            for (Need need : quest.needs()) {
                hits.add(new Hit(quest, need));
                signature.append(need.kind()).append('\u0001')
                        .append(need.spec().tag() ? '#' : 'i')
                        .append(need.spec().id()).append('\u0001')
                        .append(need.count()).append('\u0001')
                        .append(need.label()).append('\u0001')
                        .append(need.index()).append('\u0001')
                        .append(need.choice()).append('\u0002');
            }
        }
        return new Index(visibleQuests, hits, signature.toString());
    }
}
