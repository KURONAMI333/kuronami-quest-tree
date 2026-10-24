package com.kuronami.questtree.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.importer.FtbQuestsImporter;
import com.kuronami.questtree.importer.Snbt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The FTB Quests book as the importer saw it, reduced to "which KQT node, task index and reward index does this FTBQ
 * id become" (2026-10-20). Built from the same chapter files the import read, with the importer's own rules for the
 * chapter path ({@code slug} of {@code filename}), the node id (the lower-cased id string) and which tasks and rewards
 * are skipped as unknown types. The skip rule matters: an unconverted task shifts the index of every later task of its
 * quest, so counting positions in the SNBT would put progress on the wrong task.
 * <p>
 * The generated data pack drops task and reward ids (only tasks keep a {@code text} key ending in the id), which is why
 * the book is read again instead of the pack alone. The caller checks the result against the pack (node present, same
 * number of tasks and rewards) and reports a mismatch instead of guessing.
 */
public final class FtbqBook {

    /** Where a quest landed: chapter path and node id (both without the namespace). */
    public record QuestRef(String chapter, String node, String rawId) {
        public String key(String namespace) {
            return namespace + ":" + chapter + "/" + node;
        }
    }

    /**
     * A task or reward: the quest it belongs to and its position among the converted ones, or -1 when the importer
     * skipped it because the type is not supported.
     */
    public record PartRef(QuestRef quest, int index, String rawId) {
        public boolean converted() {
            return index >= 0;
        }
    }

    private final Map<Long, QuestRef> quests = new HashMap<>();
    private final Map<Long, PartRef> tasks = new HashMap<>();
    private final Map<Long, PartRef> rewards = new HashMap<>();
    private final Set<Long> chapters = new HashSet<>();

    /** Reads {@code chapters/*.snbt} below an FTB Quests quest folder. */
    public static FtbqBook read(Path questsDir) throws IOException {
        return read(questsDir, new MigrationReport());
    }

    /** As above, recording a file that is too large or will not parse and carrying on with the rest (2026-10-24). */
    public static FtbqBook read(Path questsDir, MigrationReport report) throws IOException {
        Map<String, JsonObject> files = new java.util.TreeMap<>();
        try (Stream<Path> list = Files.list(questsDir.resolve("chapters"))) {
            for (Path f : list.filter(p -> p.toString().endsWith(".snbt")).sorted().toList()) {
                String name = f.getFileName().toString();
                try {
                    if (Files.size(f) > MigrationLimits.MAX_TEXT_FILE_BYTES) {
                        report.unresolved("unreadable_file", name);
                        continue;
                    }
                    files.put(name.substring(0, name.lastIndexOf('.')),
                            Snbt.parse(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
                } catch (IOException | RuntimeException ex) {
                    // one broken chapter file must not stop the whole migration
                    report.unresolved("unreadable_file", name);
                }
            }
        }
        return of(files);
    }

    /** @param chapterFiles chapter SNBT objects by file name without extension */
    public static FtbqBook of(Map<String, JsonObject> chapterFiles) {
        FtbqBook book = new FtbqBook();
        // the book itself is object 1; FTB Quests writes it into "completed" once everything is done
        book.chapters.add(1L);
        for (Map.Entry<String, JsonObject> file : chapterFiles.entrySet()) {
            JsonObject ch = file.getValue();
            String chapter = FtbQuestsImporter.slug(ch.has("filename") && ch.get("filename").isJsonPrimitive()
                    ? ch.get("filename").getAsString() : file.getKey());
            FtbqIds.parse(ch.get("id")).ifPresent(book.chapters::add);
            for (JsonElement qe : FtbQuestsImporter.arr(ch, "quests")) {
                if (qe.isJsonObject() && qe.getAsJsonObject().has("id")) {
                    book.addQuest(chapter, qe.getAsJsonObject());
                }
            }
        }
        return book;
    }

    private void addQuest(String chapter, JsonObject q) {
        OptionalLong id = FtbqIds.parse(q.get("id"));
        if (id.isEmpty()) {
            return;
        }
        String raw = q.get("id").getAsString().toLowerCase(Locale.ROOT);
        QuestRef ref = new QuestRef(chapter, raw, raw);
        quests.putIfAbsent(id.getAsLong(), ref);
        int index = 0;
        for (JsonElement te : FtbQuestsImporter.arr(q, "tasks")) {
            if (!te.isJsonObject()) {
                continue;
            }
            JsonObject t = te.getAsJsonObject();
            boolean converted = FtbQuestsImporter.convertsTask(t);
            put(tasks, t, ref, converted ? index++ : -1);
        }
        index = 0;
        for (JsonElement re : FtbQuestsImporter.arr(q, "rewards")) {
            if (!re.isJsonObject()) {
                continue;
            }
            JsonObject r = re.getAsJsonObject();
            boolean converted = FtbQuestsImporter.convertsReward(r);
            put(rewards, r, ref, converted ? index++ : -1);
        }
    }

    private static void put(Map<Long, PartRef> into, JsonObject part, QuestRef quest, int index) {
        FtbqIds.parse(part.get("id")).ifPresent(id ->
                into.putIfAbsent(id, new PartRef(quest, index, part.get("id").getAsString().toLowerCase(Locale.ROOT))));
    }

    public QuestRef quest(long id) {
        return quests.get(id);
    }

    public PartRef task(long id) {
        return tasks.get(id);
    }

    public PartRef reward(long id) {
        return rewards.get(id);
    }

    public boolean isChapter(long id) {
        return chapters.contains(id);
    }

    public int questCount() {
        return quests.size();
    }
}
