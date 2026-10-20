package com.kuronami.questtree.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.questtree.Constants;
import com.kuronami.questtree.importer.FtbQuestsImporter;
import com.kuronami.questtree.importer.ImportOutput;
import com.kuronami.questtree.importer.PuffishImporter;
import com.kuronami.questtree.platform.Services;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * {@code /quest_tree import ftbquests|puffish [namespace]}: converts the installed quest book / skill trees into a
 * data pack inside the world ({@code datapacks/quest_tree_import}) and reloads, so a pack author can switch over in
 * one step. The original files are only read, never changed.
 */
public final class ProgressCommands {

    private static final String PACK_DIR = "quest_tree_import";

    private ProgressCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Constants.COMMAND)
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("import")
                        .then(Commands.literal("ftbquests")
                                .executes(c -> importFtb(c, "ftbquests"))
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(c -> importFtb(c, StringArgumentType.getString(c, "namespace")))))
                        .then(Commands.literal("puffish")
                                .executes(c -> importPuffish(c, "skills"))
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(c -> importPuffish(c, StringArgumentType.getString(c, "namespace"))))))
                .then(Commands.literal("status").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal(ProgressData.chapterCount() + " chapters loaded"), false);
                    return ProgressData.chapterCount();
                }))
                .then(Commands.literal("validate").executes(ProgressCommands::validate))
                .then(Commands.literal("edit")
                        .executes(c -> edit(c, null))
                        .then(Commands.literal("on").executes(c -> edit(c, true)))
                        .then(Commands.literal("off").executes(c -> edit(c, false))))
                .then(Commands.literal("export")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(c -> export(c, StringArgumentType.getString(c, "namespace"))))));
        PlayerCommands.register(dispatcher);
    }

    /**
     * Reports what the last data pack reload found wrong (ERRORs drop the node; see DATA_MODEL.md "検査"). Shows the
     * count and the first 20 problems; the full list is in the log.
     */
    private static int validate(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        List<ProgressValidator.Problem> problems = new ArrayList<>(ProgressData.problems());
        int errors = ProgressData.errorCount();
        int warnings = ProgressData.warningCount();
        if (problems.isEmpty()) {
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.none"), false);
            return 1;
        }
        src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.summary", errors, warnings), false);
        // ERRORs first: they are the ones a pack author has to act on
        problems.sort(Comparator.comparing(ProgressValidator.Problem::severity)
                .thenComparing(ProgressValidator.Problem::file)
                .thenComparing(ProgressValidator.Problem::path));
        int shown = Math.min(20, problems.size());
        for (int i = 0; i < shown; i++) {
            String line = problems.get(i).format();
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.problem", line), false);
        }
        if (problems.size() > shown) {
            src.sendSuccess(() -> Component.translatable("commands.kuronami_quest_tree.validate.more", problems.size() - shown), false);
        }
        return Math.max(0, errors);
    }

    /** Turns editing mode on/off for this player; without an argument it toggles. */
    private static int edit(CommandContext<CommandSourceStack> c, Boolean on) {
        CommandSourceStack src = c.getSource();
        net.minecraft.server.level.ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.edit.player_only",
                    "Only a player can turn editing mode on or off"));
            return 0;
        }
        boolean state = on == null ? !EditorService.isEditing(player) : on;
        EditorService.setEditing(player, state);
        src.sendSuccess(() -> Component.translatableWithFallback(
                state ? "commands.kuronami_quest_tree.edit.on" : "commands.kuronami_quest_tree.edit.off",
                state ? "Quest editing is on" : "Quest editing is off"), false);
        return state ? 1 : 0;
    }

    /**
     * Writes the validated book as a data pack for one namespace, without enabling it or reloading. This is the way
     * out of the world overlay: what the editor accepted becomes ordinary files an author can keep in a mod or pack.
     */
    private static int export(CommandContext<CommandSourceStack> c, String requested) {
        CommandSourceStack src = c.getSource();
        String ns = validNamespace(requested);
        ImportOutput out = new ImportOutput(ns);
        JsonObject bundle = ProgressData.mergedBundle();
        JsonObject chapters = bundle.has("chapters") && bundle.get("chapters").isJsonObject()
                ? bundle.getAsJsonObject("chapters") : new JsonObject();
        String prefix = ns + ":";
        int nodes = 0;
        for (Map.Entry<String, JsonElement> e : chapters.entrySet()) {
            if (!e.getKey().startsWith(prefix) || !e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject chapter = e.getValue().getAsJsonObject().deepCopy();
            String path = e.getKey().substring(prefix.length());
            out.chapters.put(path, chapter);
            JsonElement chapterNodes = chapter.get("nodes");
            if (chapterNodes != null && chapterNodes.isJsonObject()) {
                nodes += chapterNodes.getAsJsonObject().size();
            }
        }
        JsonObject tables = bundle.has("reward_tables") && bundle.get("reward_tables").isJsonObject()
                ? bundle.getAsJsonObject("reward_tables") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : tables.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue().isJsonObject()) {
                out.tables.put(e.getKey().substring(prefix.length()), e.getValue().getAsJsonObject().deepCopy());
            }
        }
        JsonElement groups = bundle.get("groups");
        if (groups != null && groups.isJsonArray()) {
            for (JsonElement el : groups.getAsJsonArray()) {
                if (el.isJsonObject() && el.getAsJsonObject().has("namespace")
                        && ns.equals(el.getAsJsonObject().get("namespace").getAsString())) {
                    out.groups.add(el.deepCopy());
                }
            }
        }
        int nodeCount = nodes;
        out.count("chapters", out.chapters.size());
        out.count("nodes", nodeCount);
        Path root = src.getServer().getWorldPath(LevelResource.DATAPACK_DIR).resolve("quest_tree_export_" + ns);
        try {
            out.writeTo(root);
        } catch (IOException ex) {
            Constants.LOG.error("Exporting progression data failed", ex);
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.export.failed",
                    "Export failed: %s", ex.getMessage()));
            return 0;
        }
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.export.done",
                "Exported %s chapters with %s nodes to %s (not enabled; review it before turning it on)",
                out.chapters.size(), nodeCount, root.getFileName()), true);
        return Math.max(1, nodeCount);
    }

    private static int importFtb(CommandContext<CommandSourceStack> c, String ns) {
        Path game = Services.PLATFORM.getGameDir();
        Path quests = Stream.of("config/ftbquests/quests", "defaultconfigs/ftbquests/quests")
                .map(game::resolve).filter(Files::isDirectory).findFirst().orElse(null);
        if (quests == null) {
            c.getSource().sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.import.ftbquests.missing", "No FTB Quests data found in config/ftbquests/quests"));
            return 0;
        }
        ImportOutput out = new ImportOutput(validNamespace(ns));
        try {
            FtbQuestsImporter.convert(quests, out);
        } catch (Exception ex) {
            Constants.LOG.error("FTB Quests import failed", ex);
            c.getSource().sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.import.failed", "Import failed: %s", ex.getMessage()));
            return 0;
        }
        return finish(c, out);
    }

    private static int importPuffish(CommandContext<CommandSourceStack> c, String ns) {
        MinecraftServer server = c.getSource().getServer();
        // categories from data packs (and mods that ship trees) ...
        Map<String, Map<String, JsonElement>> cats = new TreeMap<>();
        for (Map.Entry<ResourceLocation, Resource> e : server.getResourceManager()
                .listResources("puffish_skills/categories", id -> id.getPath().endsWith(".json")).entrySet()) {
            String path = e.getKey().getPath().substring("puffish_skills/categories/".length());
            int slash = path.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String key = e.getKey().getNamespace() + "/" + path.substring(0, slash);
            try (Reader r = e.getValue().openAsReader()) {
                cats.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(path.substring(slash + 1), JsonParser.parseReader(r));
            } catch (Exception ex) {
                Constants.LOG.warn("Skipping unreadable {}: {}", e.getKey(), ex.getMessage());
            }
        }
        // ... and from the config folder (older packs such as Prominence II keep trees there)
        Path cfg = Services.PLATFORM.getGameDir().resolve("config/puffish_skills/categories");
        if (Files.isDirectory(cfg)) {
            try (Stream<Path> dirs = Files.list(cfg)) {
                for (Path d : dirs.filter(Files::isDirectory).toList()) {
                    Map<String, JsonElement> files = cats.computeIfAbsent("config/" + d.getFileName(), k -> new LinkedHashMap<>());
                    try (Stream<Path> fs = Files.list(d)) {
                        for (Path f : fs.filter(p -> p.toString().endsWith(".json")).toList()) {
                            files.put(f.getFileName().toString(), JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)));
                        }
                    }
                }
            } catch (IOException ex) {
                Constants.LOG.warn("Could not read {}: {}", cfg, ex.getMessage());
            }
        }
        if (cats.isEmpty()) {
            c.getSource().sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.import.puffish.missing", "No Pufferfish's Skills categories found"));
            return 0;
        }
        ImportOutput out = new ImportOutput(validNamespace(ns));
        cats.forEach((k, files) -> PuffishImporter.convert(k, files, out));
        return finish(c, out);
    }

    private static int finish(CommandContext<CommandSourceStack> c, ImportOutput out) {
        CommandSourceStack src = c.getSource();
        Path root = src.getServer().getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_DIR);
        try {
            out.writeTo(root);
        } catch (IOException ex) {
            Constants.LOG.error("Writing imported data failed", ex);
            src.sendFailure(Component.translatableWithFallback("commands.kuronami_quest_tree.import.failed", "Import failed: %s", ex.getMessage()));
            return 0;
        }
        int nodes = out.counts.getOrDefault("nodes", 0) + out.counts.getOrDefault("skills", 0);
        int chapters = out.counts.getOrDefault("chapters", 0) + out.counts.getOrDefault("skill_categories", 0);
        src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.import.done", "Imported %s chapters with %s nodes into the data pack \"quest_tree_import\" (namespace %s). Reloading.", chapters, nodes, out.namespace), true);
        if (!out.unmapped.isEmpty()) {
            src.sendSuccess(() -> Component.translatableWithFallback("commands.kuronami_quest_tree.import.unmapped", "%s settings could not be carried over:", out.unmapped.size())
                    .withStyle(ChatFormatting.YELLOW), false);
            // the full list is in import_report_<ns>.json; chat shows the most frequent few
            out.unmapped.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8)
                    .forEach(e -> src.sendSuccess(() -> Component.literal("  " + e.getKey() + " x" + e.getValue()).withStyle(ChatFormatting.GRAY), false));
        }
        // newly written data packs are picked up and enabled by /reload
        src.getServer().getCommands().performPrefixedCommand(src, "reload");
        return Math.max(1, nodes);
    }

    /** Namespaces become folder names; only [a-z0-9_] so "." or ".." can never point outside the data pack. */
    static String validNamespace(String ns) {
        String s = ns.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return s.isEmpty() || s.chars().allMatch(c -> c == '_') ? "imported" : s;
    }
}
