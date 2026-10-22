package com.kuronami.questtree.client.viewer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** What a task or reward names: one item, an item tag ({@code #tag}), or a smart filter ({@code ~filter}). */
    public record Spec(SpecKind kind, String id) {

        public enum SpecKind {
            ITEM, TAG, FILTER
        }

        public Spec {
            id = id == null ? "" : id;
            kind = kind == null ? SpecKind.ITEM : kind;
        }

        public static Spec item(String id) {
            return new Spec(SpecKind.ITEM, id);
        }

        public static Spec tag(String id) {
            return new Spec(SpecKind.TAG, id);
        }

        public static Spec filter(String expression) {
            return new Spec(SpecKind.FILTER, expression);
        }

        /** Kept for the viewers: whether this is an item tag rather than a single item. */
        public boolean tag() {
            return kind == SpecKind.TAG;
        }

        public boolean filter() {
            return kind == SpecKind.FILTER;
        }

        public boolean matches(Query query) {
            if (id.isEmpty() || query == null) {
                return false;
            }
            return switch (kind) {
                case ITEM -> id.equals(query.itemId());
                case TAG -> query.inTag(id);
                case FILTER -> query.inFilter(id);
            };
        }
    }

    /**
     * One item the viewer knows about. Tags and filters are asked of the stack, so this never needs the tag registry,
     * and a filter is evaluated by the same {@code ItemFilter} the server uses to count the task.
     */
    public interface Query {
        String itemId();

        boolean inTag(String tagId);

        /** Whether the stack passes an FTB Filter System expression; viewers without game access answer false. */
        default boolean inFilter(String expression) {
            return false;
        }

        /**
         * Whether the stack satisfies the task's component check. {@code itemJson} is the item JSON the task wrote
         * (components included) and {@code matchMode} is {@code NONE}, {@code FUZZY} or {@code STRICT}; viewers
         * without game access accept everything, because the mode already says whether components matter.
         */
        default boolean accepts(String itemJson, String matchMode) {
            return true;
        }
    }

    /**
     * One item named by a task or reward. {@code index} is the task/reward index in its quest.
     * <p>
     * {@code label} is the plain fallback; {@code labelJson} is a serialized Component kept so the language is
     * applied when the row is drawn, not when the bundle arrived.
     */
    public record Need(Kind kind, Spec spec, long count, String label, String labelJson, int index, boolean choice,
                       String itemJson, String matchMode) {

        public Need {
            label = label == null ? "" : label;
            labelJson = labelJson == null ? "" : labelJson;
            itemJson = itemJson == null ? "" : itemJson;
            matchMode = matchMode == null ? "" : matchMode;
        }

        /** A need with a plain label and no components (a tag, a plain item or a reward). */
        public Need(Kind kind, Spec spec, long count, String label, int index, boolean choice) {
            this(kind, spec, count, label, "", index, choice, "", "");
        }

        /** Whether the stack named by {@code query} counts for this task or reward. */
        public boolean matches(Query query) {
            if (!spec.matches(query)) {
                return false;
            }
            if (itemJson.isEmpty() || matchMode.isEmpty() || matchMode.equals("NONE")) {
                return true;
            }
            return query.accepts(itemJson, matchMode);
        }
    }

    /** A quest as the viewer shows it: its own icon and title, its chapter, and every item it needs or gives. */
    public record Quest(String key, String chapterId, String title, String chapterTitle, String iconItemId,
                        List<Need> needs) {
        public Quest {
            needs = List.copyOf(needs);
        }
    }

    /** One viewer row. {@code order} keeps the quest/need order even when lookups come from different buckets. */
    public record Hit(Quest quest, Need need, int order) {
    }

    @FunctionalInterface
    public interface Visibility {
        boolean canSee(Quest quest);
    }

    public static final class Index {
        private static final Index EMPTY = new Index(List.of(), List.of(), Map.of(), Map.of(), List.of(), "",
                Set.of());

        private final List<Quest> quests;
        private final List<Hit> hits;
        private final Map<String, List<Hit>> byItem;
        private final Map<String, List<Hit>> byTag;
        private final List<Hit> byFilter;
        private final String signature;
        private final Set<String> questKeys;

        private Index(List<Quest> quests, List<Hit> hits, Map<String, List<Hit>> byItem,
                      Map<String, List<Hit>> byTag, List<Hit> byFilter, String signature, Set<String> questKeys) {
            this.quests = List.copyOf(quests);
            this.hits = List.copyOf(hits);
            this.byItem = freeze(byItem);
            this.byTag = freeze(byTag);
            this.byFilter = List.copyOf(byFilter);
            this.signature = signature;
            this.questKeys = Set.copyOf(questKeys);
        }

        private static Map<String, List<Hit>> freeze(Map<String, List<Hit>> map) {
            Map<String, List<Hit>> out = new HashMap<>(map.size());
            map.forEach((key, value) -> out.put(key, List.copyOf(value)));
            return Map.copyOf(out);
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

        /** Whether a quest with this key is in the current visible index. */
        public boolean containsQuest(String key) {
            return questKeys.contains(key);
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
            collect(kind, query, byItem.get(query.itemId()), out);
            // the tag ids are the ones the index knows (already parsed), not every need's string
            for (Map.Entry<String, List<Hit>> e : byTag.entrySet()) {
                if (query.inTag(e.getKey())) {
                    collect(kind, query, e.getValue(), out);
                }
            }
            collect(kind, query, byFilter, out);
            out.sort(Comparator.comparingInt(Hit::order));
            return List.copyOf(out);
        }

        private static void collect(Kind kind, Query query, List<Hit> candidates, List<Hit> out) {
            if (candidates == null) {
                return;
            }
            for (Hit hit : candidates) {
                if (hit.need().kind() == kind && hit.need().matches(query)) {
                    out.add(hit);
                }
            }
        }
    }

    /**
     * The entries of a tag in order, with no cap. The viewer used to keep the first 64, which hid the quest behind
     * any item later in a large tag; a pure copy here lets the rule be tested without the game registries.
     */
    public static <T> List<T> tagEntries(List<T> entries) {
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /** Keeps only the quests {@code visibility} allows and flattens them to one hit per task/reward item. */
    public static Index build(List<Quest> quests, Visibility visibility) {
        List<Quest> visibleQuests = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        Map<String, List<Hit>> byItem = new HashMap<>();
        Map<String, List<Hit>> byTag = new HashMap<>();
        List<Hit> byFilter = new ArrayList<>();
        Set<String> questKeys = new HashSet<>();
        StringBuilder signature = new StringBuilder();
        for (Quest quest : quests) {
            if (quest == null || (visibility != null && !visibility.canSee(quest))) {
                continue;
            }
            visibleQuests.add(quest);
            questKeys.add(quest.key());
            signature.append(quest.key()).append('\u0001')
                    .append(quest.chapterId()).append('\u0001')
                    .append(quest.title()).append('\u0001')
                    .append(quest.chapterTitle()).append('\u0001')
                    .append(quest.iconItemId()).append('\u0002');
            for (Need need : quest.needs()) {
                Hit hit = new Hit(quest, need, hits.size());
                hits.add(hit);
                switch (need.spec().kind()) {
                    case ITEM -> byItem.computeIfAbsent(need.spec().id(), key -> new ArrayList<>()).add(hit);
                    case TAG -> byTag.computeIfAbsent(need.spec().id(), key -> new ArrayList<>()).add(hit);
                    case FILTER -> byFilter.add(hit);
                }
                signature.append(need.kind()).append('\u0001')
                        .append(need.spec().kind()).append('\u0001')
                        .append(need.spec().id()).append('\u0001')
                        .append(need.count()).append('\u0001')
                        .append(need.label()).append('\u0001')
                        .append(need.labelJson()).append('\u0001')
                        .append(need.index()).append('\u0001')
                        .append(need.choice()).append('\u0001')
                        .append(need.itemJson()).append('\u0001')
                        .append(need.matchMode()).append('\u0002');
            }
        }
        return new Index(visibleQuests, hits, byItem, byTag, byFilter, signature.toString(), questKeys);
    }
}
